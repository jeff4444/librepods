/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

import AppKit
import CoreAudio
import CoreBluetooth
import Foundation
import IOBluetooth

/// The Mac's half of sharing AirPods with an Android phone (roadmap #14).
///
/// AirPods only keep two hosts at once for Apple devices, and an unrooted phone can't pass as one.
/// What they do for any paired device is let the newest connection win, and they broadcast (over
/// Bluetooth LE, readable by their owner's Mac) whether they're worn, what their current host is
/// doing and which host that is. The phone and this Mac also tell each other directly what they
/// are doing (see Beacon.swift), which is quicker than the AirPods' word. From those, each side
/// decides for itself, by priority: a call beats media, media beats idle, and short sounds
/// (notifications, beeps) count for nothing.
///
/// - Sound starts here and the other device is idle: take the AirPods. Right away if you had
///   just touched this Mac (you pressed play); otherwise only once the sound has lasted a
///   moment, so a notification never moves them.
/// - Sound starts here and the other device is really playing: the newest play wins, but only
///   if you had just touched this Mac and the sound keeps going. If you then take them straight
///   back on the other device, this Mac stops doing that for a few minutes.
/// - The other device is on a call: never.
/// - Nothing is playing anywhere, the phone is put away and you're using this Mac: bring the
///   AirPods here ahead of time, so pressing play is instant.
/// - The AirPods leave this Mac while it's playing (the phone took them): pause, and keep the
///   speakers muted until the sound has stopped, so it doesn't carry on out loud.
/// - The Mac goes to sleep: let the AirPods go, so the phone can have them.
///
/// While taking, the Mac's speakers are muted, so nothing plays out loud in between. The phone's
/// half is LibrePods on Android, with the same rules.
final class Handoff: NSObject {
    enum Place: Equatable {
        case here
        case elsewhere(ProximityAdvert.HostState)
        case free
        case notNearby
    }

    private(set) var device: IOBluetoothDevice?
    private var scanner: AdvertScanner?
    private var timer: Timer?
    var onChange: () -> Void = {}

    /// Switch on its own; off leaves only the menu's Connect and Release.
    var automatic: Bool {
        get { UserDefaults.standard.object(forKey: "automatic") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "automatic"); onChange() }
    }

    // What the AirPods last broadcast.
    private(set) var advert: ProximityAdvert?
    private var advertAt = Date.distantPast
    private var advertFresh: Bool { Date().timeIntervalSince(advertAt) < 10 }
    private(set) var bluetoothState = "starting"
    // The last three bytes of this Mac's Bluetooth address: the AirPods name their host by these.
    private let ownTail: [UInt8]

    // This Mac's link and sound.
    private(set) var linked = false
    private var playing = false
    private var playingSince = Date.distantPast
    private var worn = false
    private var wornSince = Date.distantPast
    /// Set when there is a fresh reason to take the AirPods: sound just started here, or they
    /// were just put in while sound is playing. Cleared once acted on, so the two sides don't
    /// trade them back and forth.
    private var armed = false

    // The direct signal: what the phone last said about itself, and what this Mac says.
    private var phoneSaid: Beacon.State?
    private var phoneSaidAt = Date.distantPast
    private var phoneSaidSince = Date.distantPast
    private var phoneUUIDs: [CBUUID: Beacon.State] = [:]
    private let advertiser = BeaconAdvertiser()
    private var idleSince = Date.distantPast
    private var said: Beacon.State?
    // This Mac counts as in use for this long after the last key press or mouse move, and offers
    // the AirPods to the phone ("away") only once it has also been silent for a while: a press
    // on the AirPods' stem may be about to resume what was playing here.
    private static let inUseSeconds = 10.0
    private static let silentBeforeAwaySeconds = 60.0
    // Following the device in use: no more often than this, so a glance at the other device
    // doesn't trade the AirPods back and forth.
    private static let followEverySeconds = 20.0
    private var followedAt = Date.distantPast
    private var leftAt = Date.distantPast
    // How long a sound must last before it moves the AirPods: a moment if you just touched this
    // Mac, longer otherwise or when it would interrupt the other device.
    private static let sustainAttended = 0.15
    private static let sustainOtherwise = 1.5
    // Input this close before the sound started counts as you starting it.
    private static let attendedSeconds = 5.0
    // Taking from a device that was playing: when, and until when not to after being overruled.
    private var tookFromBusyAt = Date.distantPast
    private var overruledUntil = Date.distantPast
    private var saidWhyNot = false
    private var yieldedAt = Date.distantPast
    private static let overruledWithinSeconds = 20.0
    private static let overruledForSeconds = 300.0

    private var taking = false
    private var takeStarted = Date.distantPast
    private var releasing = false
    // Outputs muted by us and why, to unmute the same ones.
    private var mutedForTake: AudioObjectID?
    private var mutedAfterLoss: AudioObjectID?
    private var lossAt = Date.distantPast
    private(set) var note = ""

    // Sounds that aren't the user playing something.
    private static let ignoredApps: Set<String> = [
        "com.apple.audio.SystemSoundServer-OSX", "com.apple.systemsoundserverd", "com.apple.coreaudiod",
        "com.apple.controlcenter", "com.apple.notificationcenterui", "com.apple.accessibility.heard",
        Bundle.main.bundleIdentifier ?? "me.kavishdevar.librepods.handoff",
    ]

    override init() {
        let address = IOBluetoothHostController.default()?.addressAsString() ?? ""
        ownTail = address.split(whereSeparator: { $0 == "-" || $0 == ":" }).suffix(3).compactMap { UInt8($0, radix: 16) }
        super.init()
    }

    func start() {
        device = Self.findAirPods()
        log("AirPods: \(device?.name ?? "none paired") \(device?.addressString ?? ""); this Mac ends \(ownTail.map { String(format: "%02x", $0) }.joined(separator: ":")); automatic \(automatic)")
        linked = device?.isConnected() ?? false
        if let address = device?.addressString {
            for state in Beacon.State.allCases { phoneUUIDs[Beacon.uuid(airPods: address, role: "phone", state: state)] = state }
        }
        scanner = AdvertScanner(onState: { [weak self] state in
            self?.bluetoothState = state
            log("Bluetooth LE: \(state)")
            self?.onChange()
        }) { [weak self] data, _, _, name in
            self?.heard(data, name: name)
        }
        scanner?.onServices = { [weak self] services in self?.heardBeacon(services) }
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.tick() }
        let center = NSWorkspace.shared.notificationCenter
        center.addObserver(self, selector: #selector(willSleep), name: NSWorkspace.willSleepNotification, object: nil)
    }

    static func findAirPods() -> IOBluetoothDevice? {
        if let address = UserDefaults.standard.string(forKey: "address") { return IOBluetoothDevice(addressString: address) }
        let paired = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []
        return paired.first { ($0.name ?? "").contains("AirPods") }
    }

    // ---- What the AirPods say ---------------------------------------------------------------

    private func heard(_ data: Data, name: String?) {
        // macOS only knows a name for (and only decrypts) broadcasts from AirPods it's paired
        // with, which keeps the neighbours' AirPods out.
        guard let name, let mine = device?.name, name == mine, let a = ProximityAdvert(data) else { return }
        let changed = a.state != advert?.state || a.worn != advert?.worn || a.holder != advert?.holder
        advert = a
        advertAt = Date()
        if changed {
            log("AirPods say: \(a.worn ? "worn" : "not worn"), host \(a.state), host address ends \(a.holder.map { String(format: "%02x", $0) }.joined(separator: ":"))")
            onChange()
        }
    }

    private func heardBeacon(_ services: [CBUUID]) {
        guard let state = services.lazy.compactMap({ self.phoneUUIDs[$0] }).first else { return }
        let now = Date()
        if state != phoneSaid || now.timeIntervalSince(phoneSaidAt) > 3 {
            phoneSaidSince = now
            log("the phone says: \(state.rawValue)")
        }
        phoneSaid = state
        phoneSaidAt = now
        if state == .taking, linked, !releasing, now.timeIntervalSince(yieldedAt) > 5 {
            // The phone is taking the AirPods for something that outranks what's here. Stop and
            // let go now: it connects about two seconds sooner than if it has to push us off.
            yieldedAt = now
            log("the phone is taking the AirPods; letting go")
            if playing { Media.pause() }
            _ = device?.closeConnection()
        }
    }

    /// Whether the device that has the AirPods is in the middle of something. The phone's own
    /// word, when it was heard in the last second, beats the AirPods', which lags: "busy" always,
    /// "idle" for as long as that lag could explain the AirPods still saying otherwise.
    private var hostBusy: Bool {
        let airPodsSay = advert?.state.busy ?? false
        guard let phoneSaid, Date().timeIntervalSince(phoneSaidAt) < 1.0 else { return airPodsSay }
        if phoneSaid == .busy || phoneSaid == .taking { return true }
        return airPodsSay && Date().timeIntervalSince(phoneSaidSince) > 8
    }

    /// Seconds since the last key press, click or mouse move on this Mac.
    private var sinceInput: TimeInterval {
        CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: ~0)!)
    }

    /// Newest play wins: take the AirPods even while the other device is playing, when you
    /// clearly started something here.
    var takeFromBusy: Bool {
        get { UserDefaults.standard.object(forKey: "takeFromBusy") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "takeFromBusy"); onChange() }
    }

    /// Follow the device in use: move the AirPods here when you start using this Mac while the
    /// phone is put away, before anything plays.
    var follow: Bool {
        get { UserDefaults.standard.object(forKey: "follow") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "follow"); onChange() }
    }

    /// Says over Bluetooth LE what this Mac is doing with the AirPods, while it has them.
    private func updateBeacon(now: Date) {
        var state: Beacon.State?
        if linked {
            if playing {
                state = .busy
            } else if sinceInput < Self.inUseSeconds || now.timeIntervalSince(idleSince) < Self.silentBeforeAwaySeconds {
                state = .idle
            } else {
                state = .away
            }
        }
        guard state != said else { return }
        said = state
        if let state, let address = device?.addressString {
            advertiser.advertise(Beacon.uuid(airPods: address, role: "mac", state: state))
        } else {
            advertiser.advertise(nil)
        }
    }

    var place: Place {
        if linked { return .here }
        guard advertFresh, let a = advert else { return .notNearby }
        if !a.state.connected { return .free }
        return .elsewhere(a.state)
    }

    var isWorn: Bool { advertFresh && advert?.worn == true }

    // ---- Deciding ---------------------------------------------------------------------------

    private func tick() {
        guard let device else { return }
        let now = Date()

        let linkedNow = device.isConnected()
        if linkedNow != linked {
            linked = linkedNow
            if linkedNow {
                idleSince = now
                gained()
            } else {
                lost()
            }
            onChange()
        }

        let apps = Audio.playingApps().filter { !Self.ignoredApps.contains($0) }
        let playingNow = !apps.isEmpty
        if playingNow && !playing {
            playingSince = now
            armed = true
            saidWhyNot = false
            log("sound started here (\(apps.joined(separator: ", ")))")
        }
        if !playingNow && playing {
            idleSince = now
            log("sound stopped here")
        }
        playing = playingNow
        if !playing { armed = false }

        let wornNow = isWorn
        if wornNow && !worn {
            wornSince = now
            if playing { armed = true }
        }
        worn = wornNow

        // The speakers stay muted after the AirPods were taken until the sound has stopped.
        if let muted = mutedAfterLoss, !playing || linked, now.timeIntervalSince(lossAt) > 0.6 {
            Audio.setMuted(muted, false)
            mutedAfterLoss = nil
            note = ""
            log("unmuted the speakers again")
            onChange()
        }

        updateBeacon(now: now)

        if taking {
            if now.timeIntervalSince(takeStarted) > 10 { finishTake(success: false, why: "timed out") }
            return
        }

        // Sound is playing here and the AirPods are elsewhere: whether to take them, and how
        // long the sound must have lasted first (nil: leave them).
        let since = max(playingSince, wornSince)
        let lastInput = now.addingTimeInterval(-sinceInput)
        // You touched this Mac just before the sound started, or since: you started it.
        let attended = lastInput.timeIntervalSince(since) > -Self.attendedSeconds
        let busy = hostBusy
        let onCall = advert?.state.onCall ?? false
        var sustain: TimeInterval?
        if automatic, armed, !linked, advertFresh, wornNow, !onCall {
            if !busy {
                sustain = attended ? Self.sustainAttended : Self.sustainOtherwise
            } else if takeFromBusy, attended, now >= overruledUntil {
                sustain = Self.sustainOtherwise
            }
        }
        if let sustain {
            // Quiet the speakers straight away; connect once the sound has lasted long enough.
            if mutedForTake == nil, let out = Audio.defaultOutput(), !out.bluetooth, Audio.muted(out.id) == false, Audio.setMuted(out.id, true) {
                mutedForTake = out.id
            }
            if now.timeIntervalSince(since) >= sustain {
                if busy { tookFromBusyAt = now }
                take(reason: busy ? "you started something here while the other device was playing" : attended ? "you started something here" : "sound has been playing here")
            }
        } else if let muted = mutedForTake {
            Audio.setMuted(muted, false)
            mutedForTake = nil
        }
        // Say once per sound why the AirPods stay where they are.
        if sustain == nil, automatic, armed, !linked, advertFresh, wornNow, !saidWhyNot, now.timeIntervalSince(since) > 0.5 {
            saidWhyNot = true
            let why = onCall ? "the other device is on a call"
                : !takeFromBusy ? "the other device is playing"
                : !attended ? "the other device is playing and you weren't using this Mac when this started"
                : "the other device is playing and took them straight back a few minutes ago"
            log("leaving the AirPods where they are: \(why)")
        }

        // Nothing is playing anywhere, the phone says it's put away, and this Mac is being used:
        // bring the AirPods here now, so that pressing play later is instant.
        if follow, !linked, !playing, advertFresh, wornNow, advert?.state.busy == false,
           phoneSaid == .away, now.timeIntervalSince(phoneSaidAt) < 2,
           sinceInput < 2,
           now.timeIntervalSince(followedAt) > Self.followEverySeconds,
           now.timeIntervalSince(leftAt) > Self.followEverySeconds {
            followedAt = now
            take(reason: "you're using this Mac and the phone is put away")
        }
    }

    // ---- Taking and letting go --------------------------------------------------------------

    /// Connects the AirPods to this Mac; the newest connection wins, so this takes them from the phone.
    func take(reason: String) {
        guard let device, !taking else { return }
        armed = false
        taking = true
        takeStarted = Date()
        note = "Connecting…"
        log("taking the AirPods (\(reason)); they say host \(advert.map { "\($0.state)" } ?? "unknown")")
        onChange()
        let result = device.openConnection(self)
        if result != kIOReturnSuccess { finishTake(success: false, why: "openConnection \(result)") }
    }

    /// IOBluetooth calls this when the connection attempt ends.
    @objc func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        guard taking else { return }
        if status != kIOReturnSuccess {
            finishTake(success: false, why: "connection failed (\(status))")
            return
        }
        log(String(format: "connected after %.2f s; waiting for the sound output", Date().timeIntervalSince(takeStarted)))
        waitForOutput(until: Date().addingTimeInterval(6))
    }

    private func waitForOutput(until deadline: Date) {
        guard taking else { return }
        let name = device?.name ?? "AirPods"
        if let out = Audio.defaultOutput(), out.bluetooth, out.name == name {
            finishTake(success: true, why: "")
            return
        }
        // macOS usually switches by itself; if the output is there and it hasn't, switch.
        if let airPods = Audio.outputs().first(where: { $0.bluetooth && $0.name == name }),
           Date().timeIntervalSince(takeStarted) > 1.0 {
            Audio.setDefaultOutput(airPods.id)
        }
        if Date() > deadline {
            finishTake(success: false, why: "no sound output appeared")
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.waitForOutput(until: deadline) }
    }

    private func finishTake(success: Bool, why: String) {
        taking = false
        if let muted = mutedForTake {
            Audio.setMuted(muted, false)
            mutedForTake = nil
        }
        let took = String(format: "%.2f s", Date().timeIntervalSince(takeStarted))
        if success {
            note = ""
            log("the AirPods are this Mac's sound output after \(took)")
        } else {
            note = "Couldn't connect"
            log("couldn't take the AirPods after \(took): \(why)")
        }
        onChange()
    }

    /// Lets the AirPods go, for the phone to pick up.
    func release(reason: String) {
        guard let device, device.isConnected() else { return }
        releasing = true
        let result = device.closeConnection()
        log("released the AirPods (\(reason)): \(result == kIOReturnSuccess ? "ok" : "error \(result)")")
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.releasing = false }
    }

    private func gained() {
        log("the AirPods are connected to this Mac")
    }

    private func lost() {
        leftAt = Date()
        if playing, leftAt.timeIntervalSince(tookFromBusyAt) < Self.overruledWithinSeconds {
            // Taken straight back, mid-song: that take was wrong. Don't interrupt the other device again for a while.
            overruledUntil = leftAt.addingTimeInterval(Self.overruledForSeconds)
            tookFromBusyAt = .distantPast
            log("the other device took them straight back; not interrupting it again for \(Int(Self.overruledForSeconds / 60)) minutes")
        }
        if releasing {
            log("the AirPods left this Mac (released)")
            return
        }
        log("the AirPods left this Mac\(playing ? " while it was playing" : "")")
        armed = false
        guard playing else { return }
        // Something else took them mid-song: don't carry on out loud.
        lossAt = Date()
        Media.pause()
        muteSpeakersAfterLoss(tries: 20)
    }

    /// The output falls back to the speakers a moment after the AirPods go; mute it as it does.
    private func muteSpeakersAfterLoss(tries: Int) {
        guard mutedAfterLoss == nil, !linked, tries > 0 else { return }
        if let out = Audio.defaultOutput(), !out.bluetooth {
            if Audio.muted(out.id) == false, Audio.setMuted(out.id, true) {
                mutedAfterLoss = out.id
                note = "Speakers muted: the AirPods moved to another device"
                log("muted \"\(out.name)\" so the sound doesn't carry on out loud")
                onChange()
            }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) { [weak self] in self?.muteSpeakersAfterLoss(tries: tries - 1) }
    }

    @objc private func willSleep() {
        release(reason: "the Mac is going to sleep")
    }
}

extension ProximityAdvert {
    /// The last three bytes of the address of the host the AirPods are connected to (macOS hands
    /// over its own AirPods' broadcasts already decrypted; bytes 4-6 of the private part).
    var holder: [UInt8] { Array([UInt8](tail)[4...6]) }
}

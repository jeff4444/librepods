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

import CoreBluetooth
import CryptoKit
import Foundation

/// The direct signal between the Mac and the phone (the phone's half is HandoffBeacon.kt in
/// LibrePods). Whichever has the AirPods broadcasts whether it is in the middle of playing
/// something. The AirPods say that too, but late: about five seconds after pausing on Android.
///
/// It says one of three things: "busy" (playing, or on a call), "idle" (not playing, but the
/// person is using that device), or "away" (not playing and nobody is using it), which lets the
/// AirPods move to the device in use before anything plays. The device without the AirPods says
/// one thing, "taking", as it starts to take them from a device that is playing: connecting is
/// about two seconds quicker when that device lets go first.
///
/// A beacon is one 128-bit service UUID and nothing else (all macOS can broadcast): the first 16
/// bytes of SHA-256("librepods-handoff/v1/<AirPods address>/<phone|mac>/<busy|idle|away|taking>").
enum Beacon {
    enum State: String, CaseIterable { case busy, idle, away, taking }

    /// `airPods` as IOBluetooth gives it ("54-2a-43-22-e8-78") or with colons.
    static func uuid(airPods: String, role: String, state: State) -> CBUUID {
        let address = airPods.uppercased().replacingOccurrences(of: "-", with: ":")
        let text = "librepods-handoff/v1/\(address)/\(role)/\(state.rawValue)"
        let hash = SHA256.hash(data: Data(text.utf8))
        return CBUUID(data: Data(hash.prefix(16)))
    }
}

/// Broadcasts this Mac's beacon.
final class BeaconAdvertiser: NSObject, CBPeripheralManagerDelegate {
    private var manager: CBPeripheralManager!
    private var wanted: CBUUID?
    private var current: CBUUID?

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }

    /// Broadcasts `uuid`; nil stops.
    func advertise(_ uuid: CBUUID?) {
        wanted = uuid
        apply()
    }

    private func apply() {
        guard manager.state == .poweredOn, wanted != current else { return }
        if current != nil { manager.stopAdvertising() }
        current = wanted
        if let uuid = wanted {
            manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [uuid]])
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state != .poweredOn { current = nil }
        apply()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error { log("couldn't broadcast the beacon: \(error.localizedDescription)") }
    }
}

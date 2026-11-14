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
import Foundation
import IOBluetooth

// LibrePods Handoff (roadmap #14): the Mac's half of sharing AirPods with an Android phone.
// Opened normally it sits in the menu bar (see Handoff.swift). With a command it runs one test
// and exits: status | audio | scan SECONDS | watch SECONDS | connect [ADDRESS] |
// disconnect [ADDRESS]; add --log FILE to also write the output there.

let agentLogURL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/LibrePodsHandoff.log")

var logFile: FileHandle?
let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    return f
}()

func log(_ message: String) {
    let line = "\(clock.string(from: Date())) \(message)\n"
    FileHandle.standardOutput.write(line.data(using: .utf8)!)
    logFile?.write(line.data(using: .utf8)!)
}

func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined(separator: " ") }

var arguments = Array(CommandLine.arguments.dropFirst())
if let i = arguments.firstIndex(of: "--log"), i + 1 < arguments.count {
    let path = arguments[i + 1]
    FileManager.default.createFile(atPath: path, contents: nil)
    logFile = FileHandle(forWritingAtPath: path)
    arguments.removeSubrange(i...(i + 1))
}

func airPods(_ address: String?) -> IOBluetoothDevice? {
    if let address { return IOBluetoothDevice(addressString: address) }
    let paired = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []
    return paired.first { ($0.name ?? "").contains("AirPods") }
}

func describeAudio() -> String {
    let out = Audio.defaultOutput()
    let running = out.map { Audio.running($0.id) } ?? false
    let apps = Audio.playingApps()
    return "output \"\(out?.name ?? "none")\"\(out?.bluetooth == true ? " (Bluetooth)" : ""), \(running ? "playing" : "silent")\(apps.isEmpty ? "" : " [\(apps.joined(separator: ", "))]")"
}

func runFor(_ seconds: Double) {
    RunLoop.main.run(until: Date().addingTimeInterval(seconds))
}

switch arguments.first ?? "" {
case "status":
    let paired = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []
    for d in paired {
        log("\(d.name ?? "?") \(d.addressString ?? "?") \(d.isConnected() ? "connected" : "not connected")")
    }
    log(describeAudio())

case "audio":
    for d in Audio.outputs() {
        log("\(d.name) [\(d.uid)]\(d.bluetooth ? " Bluetooth" : "")\(Audio.running(d.id) ? " playing" : "")")
    }
    log(describeAudio())

case "scan", "watch":
    // scan: every proximity broadcast; watch: only changes, plus this Mac's link and sound.
    let seconds = Double(arguments.dropFirst().first ?? "10") ?? 10
    let everything = arguments.first == "scan"
    var last: [UUID: Data] = [:]
    let scanner = AdvertScanner(onState: { log("Bluetooth LE: \($0)") }) { data, rssi, id, name in
        guard data.count > 2, data[data.startIndex + 2] == 0x07 else { return }
        if !everything && last[id] == data { return }
        last[id] = data
        if let a = ProximityAdvert(data) {
            log("advert \(id.uuidString.prefix(8)) \"\(name ?? "")\" rssi \(rssi) model \(String(format: "%04x", a.model)) paired \(a.paired) in ear L\(a.leftInEar ? 1 : 0) R\(a.rightInEar ? 1 : 0) host \(a.state) | \(hex(data))")
        } else {
            log("advert \(id.uuidString.prefix(8)) rssi \(rssi) | \(hex(data))")
        }
    }
    _ = scanner
    if everything {
        runFor(seconds)
    } else {
        let device = airPods(nil)
        var connected: Bool? = nil
        var audio = ""
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            let now = device?.isConnected() ?? false
            if now != connected {
                connected = now
                log("this Mac's link to \(device?.name ?? "the AirPods"): \(now ? "connected" : "not connected")")
            }
            let a = describeAudio()
            if a != audio {
                audio = a
                log(a)
            }
            runFor(0.05)
        }
    }

case "connect", "disconnect":
    guard let device = airPods(arguments.dropFirst().first) else {
        log("no paired AirPods found")
        exit(1)
    }
    log("\(device.name ?? "?") \(device.addressString ?? "?"): \(device.isConnected() ? "connected" : "not connected"); \(describeAudio())")
    let start = Date()
    func since() -> String { String(format: "+%.2f s", Date().timeIntervalSince(start)) }
    if arguments.first == "connect" {
        let result = device.openConnection()
        log("\(since()) openConnection returned \(result) (\(result == kIOReturnSuccess ? "ok" : "failed")), connected \(device.isConnected())")
        // Then wait for the Mac to offer them as a sound output, and to switch to them.
        var offered = false
        var isDefault = false
        let end = Date().addingTimeInterval(12)
        while Date() < end && !isDefault {
            if !offered, Audio.outputs().contains(where: { $0.bluetooth }) {
                offered = true
                log("\(since()) a Bluetooth sound output appeared")
            }
            if Audio.defaultOutput()?.bluetooth == true {
                isDefault = true
                log("\(since()) the Mac's output is now \"\(Audio.defaultOutput()?.name ?? "?")\"")
            }
            runFor(0.05)
        }
        log("\(since()) done: connected \(device.isConnected()); \(describeAudio())")
    } else {
        let result = device.closeConnection()
        log("\(since()) closeConnection returned \(result), connected \(device.isConnected())")
        let end = Date().addingTimeInterval(5)
        while Date() < end && device.isConnected() { runFor(0.05) }
        log("\(since()) done: connected \(device.isConnected()); \(describeAudio())")
    }

case "help", "--help", "-h":
    log("commands: status | audio | scan SECONDS | watch SECONDS | connect [ADDRESS] | disconnect [ADDRESS]   (--log FILE)")

default:
    // The menu-bar helper. One copy only.
    let me = NSRunningApplication.current
    let others = NSRunningApplication.runningApplications(withBundleIdentifier: Bundle.main.bundleIdentifier ?? "").filter { $0 != me }
    if !others.isEmpty { exit(0) }
    if logFile == nil {
        // Start afresh once the log has grown past 1 MB.
        let size = (try? FileManager.default.attributesOfItem(atPath: agentLogURL.path)[.size] as? Int) ?? nil
        if size == nil || size! > 1_000_000 { FileManager.default.createFile(atPath: agentLogURL.path, contents: nil) }
        logFile = FileHandle(forWritingAtPath: agentLogURL.path)
        logFile?.seekToEndOfFile()
    }
    let app = NSApplication.shared
    let delegate = AppDelegate()
    app.delegate = delegate
    app.setActivationPolicy(.accessory)
    app.run()
}

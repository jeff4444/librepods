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

import CoreAudio
import Foundation

/// What the Mac's sound output is doing (roadmap #14): which device is the output, whether
/// anything is playing, and which apps are playing it.
enum Audio {
    struct Device {
        let id: AudioObjectID
        let name: String
        let uid: String
        let bluetooth: Bool
    }

    private static let system = AudioObjectID(kAudioObjectSystemObject)

    private static func address(_ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    private static func number(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> UInt32? {
        var a = address(selector, scope)
        var size = UInt32(MemoryLayout<UInt32>.size)
        var value: UInt32 = 0
        return AudioObjectGetPropertyData(id, &a, 0, nil, &size, &value) == noErr ? value : nil
    }

    private static func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String {
        var a = address(selector)
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var value: Unmanaged<CFString>?
        guard AudioObjectGetPropertyData(id, &a, 0, nil, &size, &value) == noErr, let v = value else { return "" }
        return v.takeRetainedValue() as String
    }

    private static func list(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> [AudioObjectID] {
        var a = address(selector, scope)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &a, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(id, &a, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func device(_ id: AudioObjectID) -> Device {
        let transport = number(id, kAudioDevicePropertyTransportType) ?? 0
        return Device(
            id: id,
            name: string(id, kAudioObjectPropertyName),
            uid: string(id, kAudioDevicePropertyDeviceUID),
            bluetooth: transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE
        )
    }

    /// Every device that can play sound.
    static func outputs() -> [Device] {
        list(system, kAudioHardwarePropertyDevices)
            .filter { !list($0, kAudioDevicePropertyStreams, kAudioObjectPropertyScopeOutput).isEmpty }
            .map(device)
    }

    static func defaultOutput() -> Device? {
        guard let id = number(system, kAudioHardwarePropertyDefaultOutputDevice), id != 0 else { return nil }
        return device(id)
    }

    @discardableResult
    static func setDefaultOutput(_ id: AudioObjectID) -> Bool {
        var a = address(kAudioHardwarePropertyDefaultOutputDevice)
        var value = id
        return AudioObjectSetPropertyData(system, &a, 0, nil, UInt32(MemoryLayout<AudioObjectID>.size), &value) == noErr
    }

    /// Whether any app is playing through this device right now.
    static func running(_ id: AudioObjectID) -> Bool {
        (number(id, kAudioDevicePropertyDeviceIsRunningSomewhere) ?? 0) != 0
    }

    /// Bundle IDs of the apps playing sound right now (macOS 14.2 and later).
    static func playingApps() -> [String] {
        list(system, kAudioHardwarePropertyProcessObjectList).compactMap { process in
            guard (number(process, kAudioProcessPropertyIsRunningOutput) ?? 0) != 0 else { return nil }
            let bundle = string(process, kAudioProcessPropertyBundleID)
            return bundle.isEmpty ? "pid \(number(process, kAudioProcessPropertyPID) ?? 0)" : bundle
        }
    }
}

extension Audio {
    /// Whether this output is muted, or nil if it has no mute switch.
    static func muted(_ id: AudioObjectID) -> Bool? {
        number(id, kAudioDevicePropertyMute, kAudioDevicePropertyScopeOutput).map { $0 != 0 }
    }

    /// Mutes or unmutes an output; false if it has no mute switch.
    @discardableResult
    static func setMuted(_ id: AudioObjectID, _ muted: Bool) -> Bool {
        var a = address(kAudioDevicePropertyMute, kAudioDevicePropertyScopeOutput)
        var settable: DarwinBoolean = false
        guard AudioObjectHasProperty(id, &a), AudioObjectIsPropertySettable(id, &a, &settable) == noErr, settable.boolValue else { return false }
        var value: UInt32 = muted ? 1 : 0
        return AudioObjectSetPropertyData(id, &a, 0, nil, UInt32(MemoryLayout<UInt32>.size), &value) == noErr
    }
}

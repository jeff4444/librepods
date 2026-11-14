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
import Foundation

/// One of the AirPods' own Bluetooth LE broadcasts (Apple's "proximity pairing" message), which
/// say whether they're worn and what their current host is doing. Same fields as LibrePods'
/// BLEManager on Android reads.
struct ProximityAdvert: Equatable {
    /// What the AirPods' host is doing: one byte of flags. Seen on firmware 9B47 (Nov 2026):
    /// 00 no host; 04 and 08 a host that is silent (08 after a couple of minutes on a Mac, 0C
    /// for a moment after connecting); 05, 09, 0D the same with media playing (bit 0); 06 and 07
    /// a call (bit 1).
    struct HostState: Equatable, CustomStringConvertible {
        let raw: UInt8

        init(_ byte: UInt8) { raw = byte }

        var connected: Bool { raw != 0 }
        var onCall: Bool { raw & 0x02 != 0 }
        var playing: Bool { raw & 0x01 != 0 && !onCall }
        /// The host is in the middle of something the wearer is listening to.
        var busy: Bool { playing || onCall }

        var description: String {
            let what = !connected ? "none" : onCall ? "on a call" : playing ? "playing" : "idle"
            return "\(what) (0x\(String(format: "%02x", raw)))"
        }
    }

    let model: UInt16
    let paired: Bool
    let leftInEar: Bool
    let rightInEar: Bool
    let state: HostState
    /// The 16 bytes only the AirPods' owner can read (or, if macOS already decrypted them, the plain bytes).
    let tail: Data
    let raw: Data

    var worn: Bool { leftInEar || rightInEar }

    /// `data` is the manufacturer data as CoreBluetooth gives it: company ID first.
    init?(_ data: Data) {
        let d = [UInt8](data)
        // 4C 00 (Apple), 07 (proximity pairing), 19 (length), then 25 bytes.
        guard d.count >= 29, d[0] == 0x4C, d[1] == 0x00, d[2] == 0x07, d[3] == 0x19 else { return nil }
        paired = d[4] == 1
        model = UInt16(d[6]) << 8 | UInt16(d[5])
        let status = d[7]
        let primaryLeft = (status >> 5) & 1 == 1
        let thisInCase = (status >> 6) & 1 == 1
        let swapped = primaryLeft != thisInCase
        leftInEar = swapped ? status & 0x08 != 0 : status & 0x02 != 0
        rightInEar = swapped ? status & 0x02 != 0 : status & 0x08 != 0
        state = HostState(d[12])
        tail = Data(d[(d.count - 16)...])
        raw = data
    }
}

/// Listens for Bluetooth LE broadcasts and hands over Apple's.
final class AdvertScanner: NSObject, CBCentralManagerDelegate {
    private var central: CBCentralManager!
    private let onApple: (Data, Int, UUID, String?) -> Void
    private let onState: (String) -> Void
    /// Called with the service UUIDs of any broadcast that carries some (the phone's beacon does).
    var onServices: ([CBUUID]) -> Void = { _ in }

    /// `onApple` gets the manufacturer data, signal strength, and CoreBluetooth's ID and name for
    /// the sender (it only knows a name for devices this Mac is paired with).
    init(onState: @escaping (String) -> Void = { _ in }, onApple: @escaping (Data, Int, UUID, String?) -> Void) {
        self.onApple = onApple
        self.onState = onState
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            onState("on")
            central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        case .unauthorized: onState("not allowed (System Settings > Privacy & Security > Bluetooth)")
        case .poweredOff: onState("Bluetooth is off")
        case .unsupported: onState("unsupported")
        case .resetting: onState("resetting")
        default: onState("unknown")
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID], !services.isEmpty {
            onServices(services)
        }
        guard let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              data.count >= 3, data[data.startIndex] == 0x4C, data[data.startIndex + 1] == 0x00 else { return }
        onApple(data, RSSI.intValue, peripheral.identifier, peripheral.name)
    }
}

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
import ServiceManagement

/// The menu-bar item: where the AirPods are, and the few controls.
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let handoff = Handoff()
    private var item: NSStatusItem!

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.image = NSImage(systemSymbolName: "airpodspro", accessibilityDescription: "LibrePods Handoff")
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        handoff.onChange = { [weak self] in self?.refreshIcon() }
        handoff.start()
        refreshIcon()
    }

    private func refreshIcon() {
        // Dimmed while the AirPods are somewhere else.
        item.button?.appearsDisabled = handoff.place != .here
        item.button?.toolTip = status()
    }

    private func status() -> String {
        let name = handoff.device?.name ?? "AirPods"
        guard handoff.device != nil else { return "No AirPods are paired with this Mac" }
        switch handoff.place {
        case .here: return "\(name): on this Mac"
        case .free: return "\(name): not connected\(handoff.isWorn ? ", worn" : "")"
        case .notNearby: return "\(name): not nearby"
        case .elsewhere(let state):
            let doing = state.onCall ? "on a call" : state.playing ? "playing" : "idle"
            return "\(name): on another device (\(doing))"
        }
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        menu.addItem(withTitle: status(), action: nil, keyEquivalent: "")
        if !handoff.note.isEmpty { menu.addItem(withTitle: handoff.note, action: nil, keyEquivalent: "") }
        if handoff.bluetoothState != "on" { menu.addItem(withTitle: "Bluetooth: \(handoff.bluetoothState)", action: nil, keyEquivalent: "") }
        menu.addItem(.separator())
        if handoff.place == .here {
            add(menu, "Release to the Phone", #selector(releaseNow))
        } else {
            add(menu, "Connect to This Mac", #selector(takeNow)).isEnabled = handoff.device != nil
        }
        menu.addItem(.separator())
        add(menu, "Switch When Something Plays", #selector(toggleAutomatic)).state = handoff.automatic ? .on : .off
        add(menu, "Take Them Even While the Phone Is Playing", #selector(toggleTakeFromBusy)).state = handoff.takeFromBusy ? .on : .off
        add(menu, "Follow the Device I’m Using", #selector(toggleFollow)).state = handoff.follow ? .on : .off
        add(menu, "Open at Login", #selector(toggleLogin)).state = SMAppService.mainApp.status == .enabled ? .on : .off
        add(menu, "Show Log", #selector(showLog))
        menu.addItem(.separator())
        add(menu, "Quit LibrePods Handoff", #selector(quit))
    }

    @discardableResult
    private func add(_ menu: NSMenu, _ title: String, _ action: Selector) -> NSMenuItem {
        let item = menu.addItem(withTitle: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    @objc private func takeNow() { handoff.take(reason: "chosen in the menu") }
    @objc private func releaseNow() { handoff.release(reason: "chosen in the menu") }
    @objc private func toggleAutomatic() { handoff.automatic.toggle() }
    @objc private func toggleFollow() { handoff.follow.toggle() }
    @objc private func toggleTakeFromBusy() { handoff.takeFromBusy.toggle() }

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() } else { try SMAppService.mainApp.register() }
        } catch {
            log("couldn't change Open at Login: \(error.localizedDescription)")
        }
    }

    @objc private func showLog() {
        NSWorkspace.shared.open(agentLogURL)
    }

    @objc private func quit() { NSApp.terminate(nil) }
}

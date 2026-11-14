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

import Foundation

/// Pauses whatever the Mac is playing, the way the Play/Pause key does. macOS has no public call
/// for that, so this uses the system's own MediaRemote framework when it's there and reports
/// whether it could ask; callers check the sound really stopped.
enum Media {
    private typealias SendCommand = @convention(c) (UInt32, CFDictionary?) -> Bool

    private static let send: SendCommand? = {
        guard let handle = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW),
              let symbol = dlsym(handle, "MRMediaRemoteSendCommand") else { return nil }
        return unsafeBitCast(symbol, to: SendCommand.self)
    }()

    private static let playCommand: UInt32 = 0
    private static let pauseCommand: UInt32 = 1

    @discardableResult
    static func pause() -> Bool { send?(pauseCommand, nil) ?? false }

    @discardableResult
    static func play() -> Bool { send?(playCommand, nil) ?? false }
}

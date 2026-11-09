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

package me.kavishdevar.librepods.spatial

import android.util.Log
import me.kavishdevar.librepods.core.devices.AppleDevice
import kotlin.time.Duration

/**
 * Who is using the AirPods' head-motion stream (the head mouse, the spatial player, the system
 * head tracker), so one of them finishing doesn't stop it for the others.
 */
object MotionUsers {
    private val users = HashSet<String>()

    @Synchronized
    fun add(user: String) {
        users += user
    }

    /** [user] is done; stops the stream on [device] if nobody else still needs it. */
    @Synchronized
    fun release(user: String, device: AppleDevice) {
        users -= user
        if (users.isEmpty()) {
            device.setMotionStream(Duration.ZERO)
        } else {
            Log.i("MotionUsers", "$user is done; the stream stays on for ${users.joinToString()}")
        }
    }
}

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

package me.kavishdevar.librepods.services

import android.util.Log
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.data.apple.Quaternion
import me.kavishdevar.librepods.core.devices.AppleDevice
import kotlin.math.abs

/**
 * Turns the AirPods' motion into the head frame (roadmap #9): rotation rates rotated by the bud's
 * head-to-headphone transform come out as pitch about X (positive looking up) and yaw about Z
 * (positive turning right). One per consumer (head mouse, spatial player), since it keeps state.
 */
class HeadFrame(private val tag: String) {
    private var uprightDirect = 0f
    private var uprightMirror = 0f
    private var useMirror = false
    private var scoredFor: Quaternion? = null
    // A second score that looking up or down doesn't affect: the head's side axis stays level.
    private var levelDirect = 0f
    private var levelMirror = 0f

    /** Once locked, the choice stays until [unlock] (the spatial player decides once per anchor). */
    var locked = false
        private set

    fun lock() {
        locked = true
        Log.i(tag, "locked on the ${if (useMirror) "mirrored" else "sent"} head transform (upright ${"%.2f".format(uprightDirect)} vs ${"%.2f".format(uprightMirror)}, level ${"%.2f".format(levelDirect)} vs ${"%.2f".format(levelMirror)})")
    }

    fun unlock() {
        locked = false
    }

    /** The scores so far, for logs: upright and level, sent and mirrored (higher fits better). */
    fun scores() = "upright ${"%.2f".format(uprightDirect)}/${"%.2f".format(uprightMirror)}, level ${"%.2f".format(levelDirect)}/${"%.2f".format(levelMirror)}, using ${if (useMirror) "mirrored" else "sent"}"

    /**
     * The buds' transforms are mirror images (y and z negated), and the descriptor the AirPods send
     * isn't always from the bud that streams motion. The right one keeps the head's up axis
     * vertical against gravity while the head is upright (|dot| about 0.98 against 0.23 for the
     * wrong one on a test pair), so score both on every sample and use the better.
     */
    fun transformFor(device: AppleDevice, sample: MotionSample): Quaternion {
        val direct = device.headToHeadphone.value ?: DEFAULT_HEAD_TO_HEADPHONE
        val mirror = Quaternion(direct.w, direct.x, -direct.y, -direct.z)
        if (direct != scoredFor) {
            scoredFor = direct
            uprightDirect = 0f
            uprightMirror = 0f
            levelDirect = 0f
            levelMirror = 0f
        }
        fun upright(t: Quaternion): Float {
            val upInBud = t.conjugate().rotate(UP)
            return abs(sample.orientation.rotate(upInBud)[2])
        }
        fun level(t: Quaternion): Float {
            val sideInBud = t.conjugate().rotate(SIDE)
            return 1f - abs(sample.orientation.rotate(sideInBud)[2])
        }
        uprightDirect += (upright(direct) - uprightDirect) * 0.05f
        uprightMirror += (upright(mirror) - uprightMirror) * 0.05f
        levelDirect += (level(direct) - levelDirect) * 0.05f
        levelMirror += (level(mirror) - levelMirror) * 0.05f
        if (locked) return if (useMirror) mirror else direct
        val mirrorBetter = if (useMirror) uprightMirror > uprightDirect - 0.1f else uprightMirror > uprightDirect + 0.1f
        if (mirrorBetter != useMirror) {
            useMirror = mirrorBetter
            Log.i(tag, "using the ${if (useMirror) "mirrored" else "sent"} head transform (upright ${"%.2f".format(uprightDirect)} vs ${"%.2f".format(uprightMirror)})")
        }
        return if (useMirror) mirror else direct
    }

    /** The head-frame rotation rate (rad/s): [0] pitch, positive looking up; [2] yaw, positive turning right. */
    fun headRate(device: AppleDevice, sample: MotionSample): FloatArray = transformFor(device, sample).rotate(sample.rotationRate)

    companion object {
        val UP = floatArrayOf(0f, 0f, 1f)
        val SIDE = floatArrayOf(1f, 0f, 0f)

        // The left bud of one pair of AirPods Pro 3 (the right bud's is mirrored: y and z negated),
        // used only for the moment before the AirPods answer the descriptor request.
        val DEFAULT_HEAD_TO_HEADPHONE = Quaternion(w = 0.396f, x = 0.6312f, y = 0.1628f, z = 0.6466f)
    }
}

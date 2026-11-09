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

import android.os.SystemClock
import android.util.Log
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.services.HeadFrame
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Where the head points against straight ahead (roadmaps #16 and #17), from the AirPods' own fused
 * orientation (gyro plus gravity) turned into the head frame with the bud's head-to-headphone
 * transform (roadmap #9): the heading of the head's forward axis against where it pointed at the
 * anchor, its elevation against gravity, and its tilt. Nothing is integrated, so error doesn't
 * build up with movement.
 *
 * With [followWhenStill] the anchor follows the head very slowly once it has been still a while,
 * since the AirPods have no compass and their heading drifts (Android's spatializer does its own
 * recentring, so the system head tracker leaves it off).
 */
class HeadPose(private val tag: String, private val followWhenStill: Boolean) {
    /** Radians: yaw positive turning right, pitch positive looking up, roll positive tilting right. */
    @Volatile var yaw = 0f
        private set
    @Volatile var pitch = 0f
        private set
    @Volatile var roll = 0f
        private set
    /** The yaw rate (rad/s, positive turning right), smoothed, for looking ahead. */
    @Volatile var yawRate = 0f
        private set
    /** Bumped each time straight ahead is set afresh (the first sample, [recentre]). */
    @Volatile var anchors = 0
        private set

    private val headFrame = HeadFrame(tag)
    @Volatile private var anchored = false
    private var decideAt = 0L
    private var keepDirection = false
    // The head's world heading, elevation and tilt (radians) that count as straight ahead.
    private var refHeading = 0f
    private var refElevation = 0f
    private var refTilt = 0f
    private var lastHeading = 0f
    private var stillFor = 0f
    // Whether the AirPods' world turns the other way from what's assumed (checked against the gyro).
    private var headingSign = 1f
    private var agreement = 0f
    // Whether the transform's up axis points down (the upright score can't tell up from down).
    private var upSign = 1f
    // For the log: the gyro integrated without a dead zone, to compare with the orientation.
    private var gyroYaw = 0f
    private var logAt = 0L
    private var lastTimestamp: Long? = null

    /** Makes where the head points now straight ahead, from the next sample. */
    fun recentre() {
        headFrame.unlock()
        anchored = false
        yaw = 0f
        pitch = 0f
        roll = 0f
        yawRate = 0f
    }

    /** A new stream: the next sample's time isn't comparable with the last one's. */
    fun restart() {
        lastTimestamp = null
    }

    /** Takes one sample; returns true when [yaw], [pitch] and [roll] were updated from it. */
    fun update(device: AppleDevice, sample: MotionSample): Boolean {
        val previous = lastTimestamp
        lastTimestamp = sample.timestampMicros
        val transform = headFrame.transformFor(device, sample)
        // The head's axes in the AirPods' world (Z up): forward is +Y, up +Z in the head frame.
        val toWorld = sample.orientation * transform.conjugate()
        val forward = toWorld.rotate(FORWARD)
        val up = toWorld.rotate(UP)
        val heading = atan2(forward[1], forward[0])
        val elevation = asin(forward[2].coerceIn(-1f, 1f))
        if (!anchored) {
            refHeading = heading
            refElevation = elevation
            upSign = if (up[2] < 0f) -1f else 1f
            refTilt = tilt(forward, up)
            lastHeading = heading
            gyroYaw = 0f
            anchored = true
            anchors++
            decideAt = SystemClock.elapsedRealtime() + LOCK_AFTER_MS
            Log.i(tag, "anchored: straight ahead is here")
        }
        val tiltNow = tilt(forward, up)
        if (keepDirection) {
            // The transform just settled: carry on from the same direction rather than jump.
            refHeading = wrap(heading + headingSign * yaw)
            refElevation = elevation - pitch
            refTilt = tiltNow - roll
            lastHeading = heading
            keepDirection = false
        }
        // Pick the head transform early on, then keep it: flipping between the two mirror images
        // mid-song swaps how movement maps to left and right.
        if (!headFrame.locked && SystemClock.elapsedRealtime() > decideAt) {
            headFrame.lock()
            keepDirection = true
            return false
        }
        // A jump no head can make in one sample: the other bud took over the stream, with its own
        // idea of the world, or the transform flipped. Keep the direction continuous.
        val step = wrap(heading - lastHeading)
        if (abs(step) > JUMP) {
            refHeading = wrap(refHeading + step)
            Log.i(tag, "orientation jumped ${"%.0f".format(Math.toDegrees(step.toDouble()))} degrees; re-anchored (${headFrame.scores()})")
            // Probably the other bud: it may need the other transform, so decide again shortly.
            headFrame.unlock()
            decideAt = SystemClock.elapsedRealtime() + LOCK_AFTER_MS
        }
        lastHeading = heading
        if (previous == null) return false
        var elapsed = sample.timestampMicros - previous
        if (elapsed < 0) elapsed += 1L shl 32
        val dt = elapsed / 1_000_000f
        if (dt <= 0f || dt > 0.2f) return false

        val rate = transform.rotate(sample.rotationRate)
        gyroYaw += rate[2] * dt
        // The heading turns left (counter-clockwise from above) as yaw turns right; check that
        // against the gyro while the head moves, in case the AirPods' world is mirrored.
        if (abs(rate[2]) > 0.3f && abs(step) <= JUMP) {
            agreement += (-step / dt) * rate[2] * dt
            if (agreement < -3f && headingSign > 0) {
                headingSign = -1f
                Log.i(tag, "the orientation turns the other way from the gyro; flipping the heading")
            }
        }
        val still = abs(rate[2]) < DEAD_ZONE && abs(rate[0]) < DEAD_ZONE
        stillFor = if (still) stillFor + dt else 0f
        if (followWhenStill && stillFor > STILL_BEFORE_RECENTRE_S) {
            refHeading = wrap(refHeading + wrap(heading - refHeading) * (dt / YAW_RECENTRE_S))
            refElevation += (elevation - refElevation) * (dt / PITCH_RECENTRE_S)
            refTilt += wrap(tiltNow - refTilt) * (dt / PITCH_RECENTRE_S)
        }
        yaw = -headingSign * wrap(heading - refHeading)
        pitch = (elevation - refElevation).coerceIn(-1.4f, 1.4f)
        // A mirrored world mirrors the tilt too.
        roll = (headingSign * wrap(tiltNow - refTilt)).coerceIn(-1.4f, 1.4f)
        yawRate += ((if (abs(rate[2]) < DEAD_ZONE) 0f else rate[2]) - yawRate) * 0.3f
        val now = SystemClock.elapsedRealtime()
        if (now - logAt > 2000) {
            logAt = now
            Log.i(tag, "head: yaw ${deg(yaw)} (gyro alone ${deg(gyroYaw)}), pitch ${deg(pitch)}, roll ${deg(roll)}; ${headFrame.scores()}")
        }
        return true
    }

    /**
     * The head's tilt about its forward axis, positive with the right ear down: the right ear
     * axis (forward x up) dips below the horizon as up leans that way.
     */
    private fun tilt(forward: FloatArray, upRaw: FloatArray): Float {
        val ux = upRaw[0] * upSign
        val uy = upRaw[1] * upSign
        val uz = upRaw[2] * upSign
        // right = forward x up
        val rightZ = forward[0] * uy - forward[1] * ux
        return atan2(-rightZ, uz)
    }

    private fun deg(r: Float) = "%.1f".format(Math.toDegrees(r.toDouble()))

    private fun wrap(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }

    private companion object {
        val FORWARD = floatArrayOf(0f, 1f, 0f)
        val UP = floatArrayOf(0f, 0f, 1f)
        const val DEAD_ZONE = 0.025f
        // More than this in one 20 ms sample (25 rad/s) isn't a head turning.
        const val JUMP = 0.5f
        const val STILL_BEFORE_RECENTRE_S = 3f
        const val LOCK_AFTER_MS = 2500L
        const val YAW_RECENTRE_S = 40f
        const val PITCH_RECENTRE_S = 20f
    }
}

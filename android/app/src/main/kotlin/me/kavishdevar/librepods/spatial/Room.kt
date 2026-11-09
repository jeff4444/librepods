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

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A living room around the virtual speakers (roadmap #16): early reflections plus a short reverb
 * tail, so the speakers sound out in the room rather than inside the head. Fully dry headphone
 * audio tends to sit in the head; reflections arriving a little later from the walls are the main
 * cue that a sound is outside it.
 *
 * Early reflections: each speaker's first-order mirror images in a 5 x 4.2 x 2.7 m room (the
 * listener off-centre, the speakers 2 m away), each delayed by its extra path, quieter with
 * distance and the wall's absorption, and placed with simple interaural time and level differences
 * (Woodworth's ITD, a level drop and a dull far ear), turned with the head like the speakers.
 * Late reverb: a Freeverb-style network (8 combs, 4 allpasses per side) fed by both speakers.
 *
 * Coordinates are AmbiX, as in [BinauralRenderer]: x ahead, y left, z up, metres, listener at 0.
 */
class Room(private val sampleRate: Int) {
    private companion object {
        const val SPEED_OF_SOUND = 343f
        const val HEAD_RADIUS = 0.0875f
        const val SPEAKER_DISTANCE = 2f
        // Walls: ahead, behind, left, right, floor, ceiling (planes at these coordinates).
        const val FRONT = 3.0f
        const val BACK = -2.0f
        const val LEFT = 2.2f
        const val RIGHT = -2.0f
        const val FLOOR = -1.2f
        const val CEILING = 1.5f
        const val WALL_REFLECTION = 0.6f
        const val RING = 8192
        const val PRE_DELAY_S = 0.012f
    }

    // Each speaker's recent input, for delayed reads.
    private val ring = Array(2) { FloatArray(RING) }
    private var write = 0

    // Per speaker, per image: last block's delays (samples) and gains for each ear, and the far
    // ear's low-pass state.
    private val images = 6
    private val lastDelay = Array(2) { Array(images) { FloatArray(2) } }
    private val lastGain = Array(2) { Array(images) { FloatArray(2) } }
    private val lowpass = Array(2) { Array(images) { FloatArray(2) } }
    private var primed = false
    private val pos = FloatArray(3)
    private val dir = FloatArray(3)

    private val reverb = StereoReverb(sampleRate)

    /**
     * Adds the room for one block into [outL] and [outR]. [yaw] (right) and [pitch] (up) in
     * radians; [spreadDeg] is each speaker's angle from ahead; [amount] 0..1.
     */
    fun process(inL: FloatArray, inR: FloatArray, frames: Int, yaw: Float, pitch: Float, spreadDeg: Float, amount: Float, outL: FloatArray, outR: FloatArray) {
        for (i in 0 until frames) {
            val w = (write + i) and (RING - 1)
            ring[0][w] = inL[i]
            ring[1][w] = inR[i]
        }
        if (amount > 0f) {
            for (s in 0..1) {
                val az = Math.toRadians((if (s == 0) spreadDeg else -spreadDeg).toDouble()).toFloat()
                val sx = SPEAKER_DISTANCE * cos(az)
                val sy = SPEAKER_DISTANCE * sin(az)
                val sz = 0f
                for (m in 0 until images) {
                    // Mirror the speaker in wall m.
                    pos[0] = sx; pos[1] = sy; pos[2] = sz
                    when (m) {
                        0 -> pos[0] = 2 * FRONT - sx
                        1 -> pos[0] = 2 * BACK - sx
                        2 -> pos[1] = 2 * LEFT - sy
                        3 -> pos[1] = 2 * RIGHT - sy
                        4 -> pos[2] = 2 * FLOOR - sz
                        5 -> pos[2] = 2 * CEILING - sz
                    }
                    val dist = sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2])
                    headRelative(pos[0] / dist, pos[1] / dist, pos[2] / dist, yaw, pitch, dir)
                    val lateral = dir[1].coerceIn(-1f, 1f)
                    val theta = asin(abs(lateral))
                    val itd = HEAD_RADIUS / SPEED_OF_SOUND * (theta + sin(theta)) * sampleRate
                    val base = (dist - SPEAKER_DISTANCE) / SPEED_OF_SOUND * sampleRate
                    val gain = SPEAKER_DISTANCE / dist * WALL_REFLECTION * amount
                    val near = 1f + 0.15f * abs(lateral)
                    val far = 1f - 0.45f * abs(lateral)
                    // The near ear hears it first and louder.
                    val dl = if (lateral >= 0) base else base + itd
                    val dr = if (lateral >= 0) base + itd else base
                    val gl = gain * if (lateral >= 0) near else far
                    val gr = gain * if (lateral >= 0) far else near
                    if (!primed) {
                        lastDelay[s][m][0] = dl; lastDelay[s][m][1] = dr
                        lastGain[s][m][0] = gl; lastGain[s][m][1] = gr
                    }
                    // The far ear is duller: a one-pole low-pass that closes as the image moves aside.
                    val farAlpha = 1f - 0.6f * abs(lateral)
                    addImage(s, m, 0, dl, gl, if (lateral >= 0) 1f else farAlpha, frames, outL)
                    addImage(s, m, 1, dr, gr, if (lateral >= 0) farAlpha else 1f, frames, outR)
                    lastDelay[s][m][0] = dl; lastDelay[s][m][1] = dr
                    lastGain[s][m][0] = gl; lastGain[s][m][1] = gr
                }
            }
            primed = true
            reverb.process(ring, write, frames, (PRE_DELAY_S * sampleRate).roundToInt(), amount, outL, outR)
        }
        write = (write + frames) and (RING - 1)
    }

    /** Reads speaker [s]'s input at a delay sliding from last block's to [delay], into [out]. */
    private fun addImage(s: Int, m: Int, ear: Int, delay: Float, gain: Float, alpha: Float, frames: Int, out: FloatArray) {
        val d0 = lastDelay[s][m][ear]
        val g0 = lastGain[s][m][ear]
        var y = lowpass[s][m][ear]
        val src = ring[s]
        for (i in 0 until frames) {
            val f = (i + 1).toFloat() / frames
            val d = d0 + (delay - d0) * f
            val g = g0 + (gain - g0) * f
            val at = write + i - d
            val a = at.toInt().let { if (at < it) it - 1 else it }
            val frac = at - a
            val x0 = src[a and (RING - 1)]
            val x1 = src[(a + 1) and (RING - 1)]
            val x = x0 + (x1 - x0) * frac
            y += alpha * (x - y)
            out[i] += y * g
        }
        lowpass[s][m][ear] = y
    }

    /** A world unit vector seen from a head turned [yaw] right and [pitch] up (as in [BinauralRenderer]). */
    private fun headRelative(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, out: FloatArray) {
        // Turning right by yaw moves the world left: rotate by +yaw about z.
        val c = cos(yaw)
        val s = sin(yaw)
        val x1 = x * c - y * s
        val y1 = x * s + y * c
        // Looking up by pitch moves what's ahead down.
        val cp = cos(pitch)
        val sp = sin(pitch)
        out[0] = x1 * cp + z * sp
        out[1] = y1
        out[2] = -x1 * sp + z * cp
    }

    fun reset() {
        ring.forEach { it.fill(0f) }
        primed = false
        reverb.reset()
    }
}

/** Freeverb (Jezar at Dreampoint, public domain): 8 damped combs and 4 allpasses per side. */
private class StereoReverb(sampleRate: Int) {
    private val scale = sampleRate / 44_100f
    private val combL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale).roundToInt()) }
    private val combR = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(((it + 23) * scale).roundToInt()) }
    private val allL = intArrayOf(556, 441, 341, 225).map { Allpass((it * scale).roundToInt()) }
    private val allR = intArrayOf(556, 441, 341, 225).map { Allpass(((it + 23) * scale).roundToInt()) }

    init {
        // A living room: short decay (room size 0.5) with some damping.
        val feedback = 0.5f * 0.28f + 0.7f
        val damp = 0.5f * 0.4f
        (combL + combR).forEach { it.feedback = feedback; it.damp = damp }
    }

    fun process(ring: Array<FloatArray>, write: Int, frames: Int, preDelay: Int, amount: Float, outL: FloatArray, outR: FloatArray) {
        val mask = ring[0].size - 1
        val wet = 0.9f * amount
        for (i in 0 until frames) {
            val at = (write + i - preDelay) and mask
            val input = (ring[0][at] + ring[1][at]) * 0.015f
            var l = 0f
            var r = 0f
            for (c in combL) l += c.process(input)
            for (c in combR) r += c.process(input)
            for (a in allL) l = a.process(l)
            for (a in allR) r = a.process(r)
            outL[i] += l * wet
            outR[i] += r * wet
        }
    }

    fun reset() {
        (combL + combR).forEach { it.reset() }
        (allL + allR).forEach { it.reset() }
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f
        var feedback = 0.84f
        var damp = 0.2f

        fun process(input: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = input + store * feedback
            if (++idx == buf.size) idx = 0
            return out
        }

        fun reset() { buf.fill(0f); store = 0f }
    }

    private class Allpass(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0

        fun process(input: Float): Float {
            val stored = buf[idx]
            val out = stored - input
            buf[idx] = input + stored * 0.5f
            if (++idx == buf.size) idx = 0
            return out
        }

        fun reset() = buf.fill(0f)
    }
}

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

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-harmonic HRIRs (roadmap #16): the SADIE KU100 set from Resonance Audio
 * (assets/hrtf, Apache 2.0), third order, 16 channels in ACN order with SN3D normalisation, left
 * ear only (the right ear is the mirror image). A source at a direction is heard through
 * sum_k Y_k(direction) * h_k, where Y_k are the real spherical harmonics.
 *
 * Directions are AmbiX: x ahead, y to the left, z up (azimuth positive to the left). Checked
 * against the data: a source at 90 degrees left comes out 10 dB louder, and earlier, in the left ear.
 */
class ShHrir private constructor(val channels: Array<FloatArray>, val taps: Int) {
    companion object {
        const val ORDER3_CHANNELS = 16

        // ACN channels with m < 0 (the sine terms, odd in y): negated for the right ear.
        private val MIRROR = intArrayOf(1, 4, 5, 9, 10, 11)

        fun mirrorSign(acn: Int) = if (acn in MIRROR) -1f else 1f

        /** Loads the order-3 set, resampled to [sampleRate] (it's measured at 48 kHz). */
        fun load(context: Context, sampleRate: Int): ShHrir {
            val bytes = context.assets.open("hrtf/sh_hrir_order_3.wav").use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var channelCount = 0
            var rate = 48_000
            var data: ByteBuffer? = null
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                when (id) {
                    "fmt " -> {
                        channelCount = buf.getShort(pos + 10).toInt()
                        rate = buf.getInt(pos + 12)
                    }
                    "data" -> data = ByteBuffer.wrap(bytes, pos + 8, size).slice().order(ByteOrder.LITTLE_ENDIAN)
                }
                pos += 8 + size + (size and 1)
            }
            requireNotNull(data) { "no data in the HRIR file" }
            require(channelCount == ORDER3_CHANNELS) { "expected 16 channels, got $channelCount" }
            val frames = data.remaining() / 2 / channelCount
            val raw = Array(channelCount) { FloatArray(frames) }
            for (t in 0 until frames) for (k in 0 until channelCount) raw[k][t] = data.getShort().toFloat() / 32768f
            if (rate == sampleRate) return ShHrir(raw, frames)
            // Linear resampling is fine here: the responses are short and band-limited.
            val outFrames = (frames.toLong() * sampleRate / rate).toInt()
            val ratio = rate.toFloat() / sampleRate
            val resampled = Array(channelCount) { k ->
                FloatArray(outFrames) { i ->
                    val src = i * ratio
                    val a = src.toInt().coerceAtMost(frames - 1)
                    val b = (a + 1).coerceAtMost(frames - 1)
                    val f = src - a
                    // Keep the energy per second the same at a lower or higher rate.
                    (raw[k][a] * (1 - f) + raw[k][b] * f) * ratio
                }
            }
            return ShHrir(resampled, outFrames)
        }

        /** Real spherical harmonics to order 3, ACN order, SN3D, for a unit vector (AmbiX axes). */
        fun harmonics(x: Float, y: Float, z: Float, out: FloatArray) {
            val r3 = 1.7320508f
            out[0] = 1f
            out[1] = y; out[2] = z; out[3] = x
            out[4] = r3 * x * y
            out[5] = r3 * y * z
            out[6] = (3f * z * z - 1f) / 2f
            out[7] = r3 * x * z
            out[8] = r3 / 2f * (x * x - y * y)
            out[9] = 0.7905694f * y * (3f * x * x - y * y)
            out[10] = 3.8729833f * x * y * z
            out[11] = 0.6123724f * y * (5f * z * z - 1f)
            out[12] = 0.5f * z * (5f * z * z - 3f)
            out[13] = 0.6123724f * x * (5f * z * z - 1f)
            out[14] = 1.9364917f * z * (x * x - y * y)
            out[15] = 0.7905694f * x * (x * x - 3f * y * y)
        }
    }
}

/**
 * Renders a stereo signal as two virtual speakers fixed around the listener and turned against
 * the head. Each block, each speaker's direction relative to the head gives a pair of HRIRs (left
 * and right ear) built from the SH set, applied by direct convolution. When a direction moves, the
 * block is rendered with the old and new filters and crossfaded, so head turns don't click.
 */
class BinauralRenderer(private val hrir: ShHrir) {
    private val taps = hrir.taps
    private val harmonics = FloatArray(ShHrir.ORDER3_CHANNELS)

    // Per speaker: the input history the convolution needs, and its filters (current and next).
    private val history = Array(2) { FloatArray(taps - 1) }
    private val current = Array(2) { Array(2) { FloatArray(taps) } }  // [speaker][ear]
    private val next = Array(2) { Array(2) { FloatArray(taps) } }
    private val currentDir = Array(2) { FloatArray(3) }
    private var primed = false

    private var work = FloatArray(0)
    private var bufA = FloatArray(0)
    private var bufB = FloatArray(0)

    /** Fills [left] and [right] (ear filters) for a head-relative unit direction. */
    private fun build(x: Float, y: Float, z: Float, left: FloatArray, right: FloatArray) {
        ShHrir.harmonics(x, y, z, harmonics)
        left.fill(0f)
        right.fill(0f)
        for (k in 0 until ShHrir.ORDER3_CHANNELS) {
            val g = harmonics[k]
            if (g == 0f) continue
            val gr = g * ShHrir.mirrorSign(k)
            val h = hrir.channels[k]
            for (t in 0 until taps) {
                left[t] += g * h[t]
                right[t] += gr * h[t]
            }
        }
    }

    /**
     * Renders [frames] frames of [inL] and [inR] into [outL] and [outR] (added in, not replaced).
     * [dirs] holds each speaker's head-relative unit direction (x, y, z) for this block.
     */
    fun process(inL: FloatArray, inR: FloatArray, frames: Int, dirs: Array<FloatArray>, outL: FloatArray, outR: FloatArray) {
        if (work.size < taps - 1 + frames) work = FloatArray(taps - 1 + frames)
        if (bufA.size < frames) { bufA = FloatArray(frames); bufB = FloatArray(frames) }
        for (s in 0..1) {
            val d = dirs[s]
            val moved = !primed || abs(d[0] - currentDir[s][0]) + abs(d[1] - currentDir[s][1]) + abs(d[2] - currentDir[s][2]) > 0.004f
            if (moved) build(d[0], d[1], d[2], next[s][0], next[s][1])
            if (!primed) for (e in 0..1) next[s][e].copyInto(current[s][e])
            // The input with its history in front.
            history[s].copyInto(work, 0)
            (if (s == 0) inL else inR).copyInto(work, taps - 1, 0, frames)
            for (e in 0..1) {
                val out = if (e == 0) outL else outR
                convolve(work, current[s][e], frames, bufA)
                if (moved && primed) {
                    convolve(work, next[s][e], frames, bufB)
                    for (i in 0 until frames) {
                        val f = (i + 1).toFloat() / frames
                        out[i] += bufA[i] * (1 - f) + bufB[i] * f
                    }
                } else {
                    for (i in 0 until frames) out[i] += bufA[i]
                }
            }
            if (moved) {
                for (e in 0..1) next[s][e].copyInto(current[s][e])
                d.copyInto(currentDir[s])
            }
            work.copyInto(history[s], 0, frames, frames + taps - 1)
        }
        primed = true
    }

    private fun convolve(x: FloatArray, h: FloatArray, frames: Int, out: FloatArray) {
        val n = taps
        for (i in 0 until frames) {
            var acc = 0f
            val base = i + n - 1
            for (k in 0 until n) acc += h[k] * x[base - k]
            out[i] = acc
        }
    }

    fun reset() {
        history.forEach { it.fill(0f) }
        primed = false
    }

    companion object {
        /** A speaker at [azimuthDeg] (positive left) seen from a head turned [yaw] right and [pitch] up (radians). */
        fun headRelative(azimuthDeg: Float, yaw: Float, pitch: Float, out: FloatArray) {
            val a = Math.toRadians(azimuthDeg.toDouble()).toFloat() + yaw
            val x0 = cos(a)
            val y0 = sin(a)
            // Looking up by pitch puts what's ahead below.
            out[0] = x0 * cos(pitch)
            out[1] = y0
            out[2] = -x0 * sin(pitch)
            val len = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
            for (i in 0..2) out[i] /= len
        }
    }
}

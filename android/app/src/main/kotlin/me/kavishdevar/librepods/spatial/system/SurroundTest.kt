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

package me.kavishdevar.librepods.spatial.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * A 5.1 test sound for system-wide spatial audio (roadmap #17): soft noise bursts from the centre
 * speaker only, played as an ordinary 5.1 stream so Android's spatializer renders it (it only
 * spatializes multichannel audio on some phones). With head tracking working, the bursts stay in
 * front of where you faced when it started as you turn.
 */
object SurroundTest {
    private const val TAG = "SurroundTest"
    private const val RATE = 48_000
    private const val CHANNELS = 6 // FL, FR, FC, LFE, BL, BR
    private const val CENTRE = 2
    private const val MAX_SECONDS = 120

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    @Synchronized
    fun toggle(context: Context) {
        if (thread != null) {
            stop()
            return
        }
        stopRequested = false
        _playing.value = true
        val ctx = context.applicationContext
        thread = Thread({ run(ctx) }, TAG).also { it.start() }
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        thread?.join(1000)
        thread = null
        _playing.value = false
    }

    private fun run(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { if (it <= 0) stopRequested = true }
            .build()
        audio.requestAudioFocus(focus)
        var track: AudioTrack? = null
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, RATE / 10 * CHANNELS * 4))
                .build()
            track.play()
            val block = RATE / 50
            val buf = FloatArray(block * CHANNELS)
            // Pink-ish noise (Paul Kellet's economy filter), in bursts: three short, a pause.
            var b0 = 0f; var b1 = 0f; var b2 = 0f
            val random = Random(17)
            var n = 0L
            val cycle = RATE * 6 / 5
            val burst = RATE / 6
            val gap = RATE / 4
            while (!stopRequested && n < RATE.toLong() * MAX_SECONDS) {
                buf.fill(0f)
                for (i in 0 until block) {
                    val t = ((n + i) % cycle).toInt()
                    val slot = t / (burst + gap)
                    val inSlot = t % (burst + gap)
                    val env = if (slot < 3 && inSlot < burst) {
                        // 10 ms fade in and out so the bursts don't click.
                        val fade = RATE / 100
                        minOf(1f, inSlot.toFloat() / fade, (burst - inSlot).toFloat() / fade)
                    } else 0f
                    val white = random.nextFloat() * 2f - 1f
                    b0 = 0.99765f * b0 + white * 0.0990460f
                    b1 = 0.96300f * b1 + white * 0.2965164f
                    b2 = 0.57000f * b2 + white * 1.0526913f
                    val pink = (b0 + b1 + b2 + white * 0.1848f) * 0.11f
                    buf[i * CHANNELS + CENTRE] = pink * env * 0.6f
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                n += block
            }
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't play the test sound", t)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            audio.abandonAudioFocusRequest(focus)
            _playing.value = false
            synchronized(this) { if (Thread.currentThread() == thread) thread = null }
        }
    }
}

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

package me.kavishdevar.librepods.presentation.screens.pixelbuds

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.PixelBudsDevice
import me.kavishdevar.librepods.core.devices.PixelBudsFitResult
import kotlin.math.roundToInt

/**
 * The ear tip seal check, as Google's app runs it: test mode on, then the phone plays a short
 * test sound at about half volume while each bud measures how much of it leaks (fit) and how
 * loud the room is (noise). Media volume is put back afterwards.
 */
class PixelBudsFitTest(private val context: Context, private val device: PixelBudsDevice) {
    data class State(
        val running: Boolean = false,
        val left: PixelBudsFitResult? = null,
        val right: PixelBudsFitResult? = null,
        val ran: Boolean = false,
    )

    private companion object {
        const val TAG = "PixelBudsFitTest"
        // Google's MaestroOtts__volume_ratio_lr for the Pro 2.
        const val VOLUME = 0.53f
        const val TIMEOUT_MS = 20_000L
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { }
        .build()
    private val timeout = Runnable { Log.i(TAG, "timed out"); finish() }

    fun start() {
        if (_state.value.running) return
        _state.value = State(running = true)
        audio.requestAudioFocus(focus)
        main.postDelayed(timeout, TIMEOUT_MS)
        device.startFitTest(
            onReady = { main.post(::playSound) },
            onResult = { left, result ->
                _state.update { if (left) it.copy(left = result) else it.copy(right = result) }
                val s = _state.value
                // Leave a moment for later readings before stopping the sound.
                if (s.left != null && s.right != null) main.postDelayed(::finish, 1500)
            },
            onDone = { main.post(::finish) },
        )
    }

    private fun playSound() {
        if (!_state.value.running) return
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * VOLUME).roundToInt(), 0) }
        player = MediaPlayer.create(context, R.raw.pixel_buds_fit_test_sound)?.apply {
            setOnCompletionListener { Log.i(TAG, "test sound finished") }
            start()
        }
    }

    fun finish() {
        if (!_state.value.running) return
        main.removeCallbacks(timeout)
        player?.runCatching { stop(); release() }
        player = null
        if (savedVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
        savedVolume = -1
        audio.abandonAudioFocusRequest(focus)
        device.endFitTest()
        _state.update { it.copy(running = false, ran = true) }
    }
}

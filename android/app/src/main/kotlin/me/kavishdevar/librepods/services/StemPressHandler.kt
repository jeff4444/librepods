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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.utils.MediaController

private const val TAG = "StemPressHandler"

/**
 * Acts on stem presses the AirPods forward to the phone.
 *
 * The AirPods forward only the press types set in RAW_GESTURES_CONFIG (see
 * [AppleDevice.applyStemPressForwarding]) and stop handling those themselves, so every forwarded
 * press must be acted on here, whatever it is mapped to, including Play/Pause. Presses that aren't
 * forwarded were already handled by the AirPods and are ignored.
 */
class StemPressHandler(private val context: Context) {

    fun onStemPress(device: AppleDevice, bud: StemPressBud, type: StemPressType, onCall: Boolean) {
        // While the head mouse runs it borrows these presses (they are forwarded for it).
        if (!onCall && HeadMouse.running.value) {
            HeadMouse.onStemPress(bud, type)
            return
        }
        val settings = device.settings.value
        if (onCall || type !in settings.forwardedPressTypes()) {
            Log.d(TAG, "$bud $type is handled by the AirPods, ignoring")
            return
        }
        val action = settings.stemAction(bud, type)
        Log.i(TAG, "$bud $type -> $action")
        execute(device, action, bud, type)
    }

    private fun execute(device: AppleDevice, action: StemAction, bud: StemPressBud, type: StemPressType) {
        when (action) {
            StemAction.PLAY_PAUSE -> MediaController.sendUserPlayPause()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.VOLUME_UP, StemAction.VOLUME_DOWN -> {
                context.getSystemService(AudioManager::class.java).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (action == StemAction.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            StemAction.CYCLE_listening_mode_MODES -> cycleListeningMode(device)
            StemAction.DIGITAL_ASSISTANT -> launchDigitalAssistant()
            // For automation apps: an intent-received trigger on this action, with "bud" (left or
            // right) and "press" (single, double, triple or long) to tell the 8 presses apart
            StemAction.AUTOMATION -> {
                val budName = if (bud == StemPressBud.LEFT) "left" else "right"
                val pressName = when (type) {
                    StemPressType.SINGLE_PRESS -> "single"
                    StemPressType.DOUBLE_PRESS -> "double"
                    StemPressType.TRIPLE_PRESS -> "triple"
                    StemPressType.LONG_PRESS -> "long"
                }
                Log.d(TAG, "Broadcasting $budName $pressName press for automation apps")
                context.sendBroadcast(Intent(StemAction.STEM_PRESS_BROADCAST).apply {
                    putExtra(StemAction.EXTRA_BUD, budName)
                    putExtra(StemAction.EXTRA_PRESS, pressName)
                })
            }
            StemAction.NOTHING -> {}
            StemAction.HEAD_MOUSE -> HeadMouse.toggle()
        }
    }

    /**
     * Moves to the next listening mode among those enabled for press and hold, as the AirPods do
     * when they handle the press themselves. Modes are 1 Off, 2 Noise Cancellation,
     * 3 Transparency, 4 Adaptive; mode n is enabled when bit (n - 1) of LISTENING_MODE_CONFIGS is set.
     */
    private fun cycleListeningMode(device: AppleDevice) {
        val controlStates = device.state.value.controlStates
        val current = controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 0
        val configs = controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS]?.getOrNull(0)?.toInt() ?: 0
        val enabled = (1..4).filter { mode -> configs and (1 shl (mode - 1)) != 0 }
            .ifEmpty { listOf(2, 3) }
        val next = enabled.firstOrNull { it > current } ?: enabled.first()
        Log.d(TAG, "Cycling listening mode $current -> $next (enabled: $enabled)")
        device.setControlCommand(ControlCommandIdentifier.LISTENING_MODE, next)
    }

    private fun launchDigitalAssistant() {
        val defaultAssistantPackage = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
                ?.takeIf { it.isNotBlank() }
                ?: Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
                    ?.takeIf { it.isNotBlank() }
        }.getOrNull()
            ?.let(ComponentName::unflattenFromString)
            ?.packageName
            ?.takeIf { it.isNotBlank() }

        val candidates = buildList {
            if (defaultAssistantPackage != null) {
                add(Intent(Intent.ACTION_VOICE_COMMAND).setPackage(defaultAssistantPackage))
                add(Intent(Intent.ACTION_ASSIST).setPackage(defaultAssistantPackage))
            }
            add(Intent(Intent.ACTION_ASSIST))
            add(Intent(Intent.ACTION_VOICE_COMMAND))
        }

        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.d(TAG, "Launched digital assistant with ${intent.action} ${intent.`package` ?: ""}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't launch ${intent.action} ${intent.`package` ?: ""}: ${e.message}")
            }
        }
        Log.e(TAG, "No digital assistant could be launched")
    }
}

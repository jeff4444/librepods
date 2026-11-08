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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.data.apple.Quaternion
import me.kavishdevar.librepods.core.devices.AppleDevice
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "HeadMouse"

/**
 * Head mouse (roadmap #11): a cursor that follows head rotation, with stem presses to tap
 * (single), recentre (double) and turn it off (press and hold).
 */
object HeadMouse {
    /** Whether the head mouse should be on: the settings toggle and the stem action set this. */
    val requested = MutableStateFlow(false)

    /** Whether it is running: cursor shown and head motion streaming. */
    val running = MutableStateFlow(false)

    internal var controller: HeadMouseController? = null

    fun toggle() {
        requested.value = !requested.value
    }

    fun tap() = controller?.tap()

    fun recenter() = controller?.recenter()
}

/**
 * Moves the cursor like a mouse: head rotation speed sets cursor speed, so the slow yaw drift an
 * absolute head angle would have (AirPods have no compass) never moves it. Rotation rates come
 * from the AirPods' gyro, turned into the head frame with their own head-to-headphone transform
 * (roadmap #9): yaw about Z (positive turning right), pitch about X (positive looking up).
 */
class HeadMouseController(
    private val scope: CoroutineScope,
    private val onRunningChanged: (AppleDevice) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    private var device: AppleDevice? = null
    private var accessibility: LibrePodsAccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var cursor: CursorView? = null
    private var jobs = emptyList<Job>()

    private var x = 0f
    private var y = 0f
    private var width = 1
    private var height = 1
    private var lastTimestamp: Long? = null
    @Volatile private var lastSampleAt = 0L

    val currentDevice: AppleDevice?
        get() = device

    fun start(device: AppleDevice, accessibility: LibrePodsAccessibilityService) {
        main.post {
            if (this.device != null) return@post
            this.device = device
            this.accessibility = accessibility

            val wm = accessibility.getSystemService(WindowManager::class.java)
            windowManager = wm
            val bounds = wm.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            x = width / 2f
            y = height / 2f

            val view = CursorView(accessibility).also { it.moveTo(x, y) }
            try {
                wm.addView(view, layoutParams())
                cursor = view
            } catch (e: Exception) {
                Log.e(TAG, "couldn't add the cursor", e)
            }

            lastTimestamp = null
            lastSampleAt = SystemClock.elapsedRealtime()
            device.setMotionStream(STREAM_INTERVAL)
            jobs = listOf(
                scope.launch { device.motionSamples.collect { onSample(it) } },
                // The stream stops when the bud carrying it leaves an ear (roadmap #8); restart it.
                scope.launch {
                    while (isActive) {
                        delay(1_000)
                        if (SystemClock.elapsedRealtime() - lastSampleAt > 1_500) {
                            Log.i(TAG, "no motion for a while, restarting the stream")
                            device.setMotionStream(STREAM_INTERVAL)
                            lastSampleAt = SystemClock.elapsedRealtime()
                        }
                    }
                },
            )
            HeadMouse.running.value = true
            Log.i(TAG, "started, screen ${width}x$height, transform ${device.headToHeadphone.value}")
            onRunningChanged(device)
        }
    }

    fun stop() {
        main.post {
            val device = device ?: return@post
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            device.setMotionStream(Duration.ZERO)
            cursor?.let { view -> runCatching { windowManager?.removeView(view) } }
            cursor = null
            this.device = null
            accessibility = null
            HeadMouse.running.value = false
            Log.i(TAG, "stopped")
            onRunningChanged(device)
        }
    }

    fun tap() {
        main.post {
            val service = accessibility ?: return@post
            val tapped = service.tap(x, y)
            Log.d(TAG, "tap at ${x.toInt()},${y.toInt()}: $tapped")
            cursor?.pulse()
        }
    }

    fun recenter() {
        main.post {
            x = width / 2f
            y = height / 2f
            cursor?.moveTo(x, y)
        }
    }

    private fun onSample(sample: MotionSample) {
        lastSampleAt = SystemClock.elapsedRealtime()
        val device = device ?: return
        val previous = lastTimestamp
        lastTimestamp = sample.timestampMicros
        if (previous == null) return
        var elapsed = sample.timestampMicros - previous
        if (elapsed < 0) elapsed += 1L shl 32
        val dt = elapsed / 1_000_000f
        if (dt <= 0f || dt > 0.2f) return

        val rate = (device.headToHeadphone.value ?: DEFAULT_HEAD_TO_HEADPHONE).rotate(sample.rotationRate)
        val speed = device.settings.value.headMouseSpeed
        // Pixels per radian of head turn: about 40 degrees sweeps the screen's width at speed 1.
        val base = width / 0.7f * speed

        fun move(radiansPerSecond: Float): Float {
            val magnitude = abs(radiansPerSecond)
            if (magnitude < DEAD_ZONE) return 0f
            // Slow turns move precisely; fast turns cover distance.
            return sign(radiansPerSecond) * (magnitude - DEAD_ZONE) * base * (1f + ACCELERATION * magnitude) * dt
        }

        val newX = (x + move(rate[2])).coerceIn(0f, width - 1f)
        val newY = (y - move(rate[0])).coerceIn(0f, height - 1f)
        main.post {
            x = newX
            y = newY
            cursor?.moveTo(x, y)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Cover the whole screen, status bar included, so view and tap coordinates match.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "LibrePods head mouse"
    }

    @SuppressLint("ViewConstructor")
    private class CursorView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var cx = 0f
        private var cy = 0f
        private var pulseUntil = 0L

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); style = Paint.Style.FILL }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }

        fun moveTo(x: Float, y: Float) {
            cx = x
            cy = y
            invalidate()
        }

        fun pulse() {
            pulseUntil = SystemClock.uptimeMillis() + 150
            invalidate()
            postDelayed({ invalidate() }, 160)
        }

        override fun onDraw(canvas: Canvas) {
            val pressed = SystemClock.uptimeMillis() < pulseUntil
            val r = (if (pressed) 8f else 11f) * density
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, dark)
            // A light outer ring keeps the cursor visible on dark and light screens.
            canvas.drawCircle(cx, cy, r + 2f * density, light)
        }
    }

    private companion object {
        val STREAM_INTERVAL = 20.milliseconds

        // Below this rotation rate (rad/s, about 1.4 deg/s) the head counts as still.
        const val DEAD_ZONE = 0.025f
        const val ACCELERATION = 1.5f

        // Measured on one pair of AirPods Pro 3, used until the AirPods send their own.
        val DEFAULT_HEAD_TO_HEADPHONE = Quaternion(w = 0.396f, x = 0.6312f, y = 0.1628f, z = 0.6466f)
    }
}

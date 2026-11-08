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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.data.apple.Quaternion
import me.kavishdevar.librepods.core.devices.AppleDevice
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "HeadMouse"

/**
 * Head mouse (roadmap #11): a cursor that follows head rotation, driven by stem presses.
 *
 * Left stem: single = click, double = double-click, triple = scroll mode (tilt to scroll),
 * hold = grab (drag with your head; single press drops). Right stem: single = Back,
 * double = recentre, triple = Home, hold = head mouse off.
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

    fun onStemPress(bud: StemPressBud, type: StemPressType) = controller?.onStemPress(bud, type)
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

    // Cursor position, screen pixels. Written on the main thread.
    private var x = 0f
    private var y = 0f
    private var width = 1
    private var height = 1
    private var lastTimestamp: Long? = null
    @Volatile private var lastSampleAt = 0L

    private enum class Mode { POINT, GRAB, SCROLL }
    @Volatile private var mode = Mode.POINT

    // Which of the two mirrored transforms fits the streaming bud, scored against gravity.
    private var uprightDirect = 0f
    private var uprightMirror = 0f
    private var useMirror = false
    private var scoredFor: Quaternion? = null

    // Scroll mode: head pitch since entering it, radians (positive = looking up).
    private var scrollPitch = 0f

    private val finger = Finger()

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
            mode = Mode.POINT

            val view = CursorView(accessibility).also { it.moveTo(x, y) }
            try {
                wm.addView(view, layoutParams())
                cursor = view
            } catch (e: Exception) {
                Log.e(TAG, "couldn't add the cursor", e)
            }

            lastTimestamp = null
            lastSampleAt = SystemClock.elapsedRealtime()
            jobs = listOf(
                scope.launch { device.motionSamples.collect { onSample(it) } },
                scope.launch {
                    startStream(device)
                    // The stream stops when the bud carrying it leaves an ear (roadmap #8); restart it.
                    while (isActive) {
                        delay(1_000)
                        if (SystemClock.elapsedRealtime() - lastSampleAt > 2_500) {
                            Log.i(TAG, "no motion for a while, restarting the stream")
                            startStream(device)
                        }
                    }
                },
            )
            main.post(finger.pump)
            HeadMouse.running.value = true
            Log.i(TAG, "started, screen ${width}x$height, transform ${device.headToHeadphone.value}")
            onRunningChanged(device)
        }
    }

    /**
     * Each bud has its own (mirrored) head-to-headphone transform, and the AirPods don't always send
     * their descriptors on connecting, so ask for them first; a stalled stream usually means the
     * other bud now carries it. Asking resets the AirPods' sensor streams, so the stream starts
     * only after the answer has had time to arrive.
     */
    private suspend fun startStream(device: AppleDevice) {
        device.aacp.requestSensorDescriptors()
        delay(600)
        device.setMotionStream(STREAM_INTERVAL)
        lastSampleAt = SystemClock.elapsedRealtime()
    }

    fun stop() {
        main.post {
            val device = device ?: return@post
            finger.cancel()
            main.removeCallbacks(finger.pump)
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            device.setMotionStream(Duration.ZERO)
            cursor?.let { view -> runCatching { windowManager?.removeView(view) } }
            cursor = null
            this.device = null
            accessibility = null
            mode = Mode.POINT
            HeadMouse.running.value = false
            Log.i(TAG, "stopped")
            onRunningChanged(device)
        }
    }

    fun onStemPress(bud: StemPressBud, type: StemPressType) {
        main.post {
            val service = accessibility ?: return@post
            Log.d(TAG, "$bud $type in $mode")
            when (bud) {
                StemPressBud.LEFT -> when (type) {
                    StemPressType.SINGLE_PRESS -> when (mode) {
                        Mode.GRAB -> drop()
                        Mode.SCROLL -> setMode(Mode.POINT)
                        Mode.POINT -> {
                            service.tap(x, y)
                            cursor?.pulse()
                        }
                    }
                    StemPressType.DOUBLE_PRESS -> {
                        if (mode != Mode.POINT) setMode(Mode.POINT)
                        service.doubleTap(x, y)
                        cursor?.pulse()
                    }
                    StemPressType.TRIPLE_PRESS -> setMode(if (mode == Mode.SCROLL) Mode.POINT else Mode.SCROLL)
                    StemPressType.LONG_PRESS -> if (mode == Mode.GRAB) drop() else setMode(Mode.GRAB)
                }
                StemPressBud.RIGHT -> when (type) {
                    StemPressType.SINGLE_PRESS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    StemPressType.DOUBLE_PRESS -> {
                        setMode(Mode.POINT)
                        x = width / 2f
                        y = height / 2f
                        cursor?.moveTo(x, y)
                    }
                    StemPressType.TRIPLE_PRESS -> {
                        setMode(Mode.POINT)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                    StemPressType.LONG_PRESS -> HeadMouse.requested.value = false
                }
            }
        }
    }

    private fun setMode(new: Mode) {
        if (new == mode) return
        // Leaving grab or scroll lifts the finger without a fling.
        if (mode != Mode.POINT) finger.up()
        mode = new
        when (new) {
            Mode.GRAB -> finger.down(x, y)
            Mode.SCROLL -> scrollPitch = 0f
            Mode.POINT -> {}
        }
        cursor?.setMode(new.ordinal)
        Log.i(TAG, "mode $new")
    }

    private fun drop() = setMode(Mode.POINT)

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

        val rate = transformFor(device, sample).rotate(sample.rotationRate)
        val speed = device.settings.value.headMouseSpeed

        main.post {
            if (mode == Mode.SCROLL) scroll(rate[0], dt, speed) else point(rate, dt, speed)
        }
    }

    /**
     * The buds' transforms are mirror images (y and z negated), and the descriptor the AirPods send
     * isn't always from the bud that streams motion. The right one keeps the head's up axis
     * vertical against gravity while the head is upright (|dot| about 0.98 against 0.23 for the
     * wrong one on a test pair), so score both on every sample and use the better.
     */
    private fun transformFor(device: AppleDevice, sample: MotionSample): Quaternion {
        val direct = device.headToHeadphone.value ?: DEFAULT_HEAD_TO_HEADPHONE
        val mirror = Quaternion(direct.w, direct.x, -direct.y, -direct.z)
        if (direct != scoredFor) {
            scoredFor = direct
            uprightDirect = 0f
            uprightMirror = 0f
        }
        fun upright(t: Quaternion): Float {
            val upInBud = t.conjugate().rotate(UP)
            return abs(sample.orientation.rotate(upInBud)[2])
        }
        uprightDirect += (upright(direct) - uprightDirect) * 0.05f
        uprightMirror += (upright(mirror) - uprightMirror) * 0.05f
        val mirrorBetter = if (useMirror) uprightMirror > uprightDirect - 0.1f else uprightMirror > uprightDirect + 0.1f
        if (mirrorBetter != useMirror) {
            useMirror = mirrorBetter
            Log.i(TAG, "using the ${if (useMirror) "mirrored" else "sent"} head transform (upright ${"%.2f".format(uprightDirect)} vs ${"%.2f".format(uprightMirror)})")
        }
        return if (useMirror) mirror else direct
    }

    private fun point(rate: FloatArray, dt: Float, speed: Float) {
        // Pixels per radian of head turn: about 40 degrees sweeps the screen's width at speed 1.
        val base = width / 0.7f * speed

        fun move(radiansPerSecond: Float): Float {
            val magnitude = abs(radiansPerSecond)
            if (magnitude < DEAD_ZONE) return 0f
            // Slow turns move precisely; fast turns cover distance.
            return sign(radiansPerSecond) * (magnitude - DEAD_ZONE) * base * (1f + ACCELERATION * magnitude) * dt
        }

        x = (x + move(rate[2])).coerceIn(0f, width - 1f)
        y = (y - move(rate[0])).coerceIn(0f, height - 1f)
        cursor?.moveTo(x, y)
        if (mode == Mode.GRAB) finger.moveTo(x, y)
    }

    /**
     * Tilting the head down from where scroll mode started scrolls down, up scrolls up, faster
     * the further the tilt. A virtual finger drags the page; it lifts when the head is level (so
     * it never long-presses) and starts again from the cursor before it reaches the screen edge.
     */
    private fun scroll(pitchRate: Float, dt: Float, speed: Float) {
        scrollPitch += pitchRate * dt
        val tilt = abs(scrollPitch) - SCROLL_DEAD_ZONE
        if (tilt <= 0f) {
            if (finger.isDown) finger.up()
            return
        }
        // Looking down (pitch < 0) scrolls down: the finger moves up.
        val fingerSpeed = tilt * SCROLL_GAIN * height * speed
        val step = (if (scrollPitch < 0) -1f else 1f) * fingerSpeed * dt
        if (!finger.isDown) finger.down(x, y)
        val target = finger.targetY + step
        if (target < EDGE_MARGIN || target > height - EDGE_MARGIN) {
            // Out of room: lift and put the finger back down at the cursor.
            finger.up()
            finger.down(x, y)
        } else {
            finger.moveTo(finger.targetX, target)
        }
    }

    /**
     * A finger held on the screen through the accessibility service, sent as a continued gesture
     * in short segments so it can stay down as long as needed while following a target point.
     */
    private inner class Finger {
        private var stroke: GestureDescription.StrokeDescription? = null
        private var fx = 0f
        private var fy = 0f
        var targetX = 0f
            private set
        var targetY = 0f
            private set
        private var inFlight = false
        private var lifting = false

        val isDown: Boolean
            get() = stroke != null && !lifting

        val pump: Runnable = object : Runnable {
            override fun run() {
                send()
                main.postDelayed(this, 16)
            }
        }

        fun down(x: Float, y: Float) {
            if (stroke != null) return
            fx = x; fy = y; targetX = x; targetY = y
            lifting = false
            val path = Path().apply { moveTo(x, y) }
            val first = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
            stroke = first
            dispatch(first)
        }

        fun moveTo(x: Float, y: Float) {
            targetX = x.coerceIn(0f, width - 1f)
            targetY = y.coerceIn(0f, height - 1f)
        }

        fun up() {
            if (stroke != null) lifting = true
        }

        fun cancel() {
            stroke = null
            lifting = false
            inFlight = false
        }

        private fun send() {
            val current = stroke ?: return
            if (inFlight) return
            val path = Path().apply {
                moveTo(fx, fy)
                // Lift in place, so letting go doesn't fling the page.
                if (!lifting && (targetX != fx || targetY != fy)) lineTo(targetX, targetY)
            }
            val next = current.continueStroke(path, 0, SEGMENT_MS, !lifting)
            if (!lifting) {
                fx = targetX
                fy = targetY
            }
            if (lifting) {
                stroke = null
                lifting = false
            } else {
                stroke = next
            }
            dispatch(next)
        }

        private fun dispatch(s: GestureDescription.StrokeDescription) {
            val service = accessibility ?: return
            inFlight = true
            val sent = service.dispatch(GestureDescription.Builder().addStroke(s).build()) { completed ->
                main.post {
                    inFlight = false
                    if (!completed) {
                        // Something else touched the screen; the gesture is over.
                        Log.d(TAG, "finger gesture cancelled")
                        stroke = null
                        lifting = false
                    }
                }
            }
            if (!sent) {
                inFlight = false
                stroke = null
            }
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
        private var mode = 0

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density }

        fun moveTo(x: Float, y: Float) {
            cx = x
            cy = y
            invalidate()
        }

        /** 0 pointing (white), 1 grabbing (orange), 2 scrolling (blue). */
        fun setMode(mode: Int) {
            this.mode = mode
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
            fill.color = when (mode) {
                1 -> 0xCCFF9500.toInt()
                2 -> 0xCC0A84FF.toInt()
                else -> 0x99FFFFFF.toInt()
            }
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, dark)
            // A light outer ring keeps the cursor visible on dark and light screens.
            canvas.drawCircle(cx, cy, r + 2f * density, light)
        }
    }

    private companion object {
        val STREAM_INTERVAL = 20.milliseconds
        val UP = floatArrayOf(0f, 0f, 1f)
        const val SEGMENT_MS = 40L

        // Below this rotation rate (rad/s, about 1.4 deg/s) the head counts as still.
        const val DEAD_ZONE = 0.025f
        const val ACCELERATION = 1.5f

        // Scroll mode: tilt beyond about 4 degrees scrolls; at 0.3 rad past that the finger moves
        // the screen's height in about 1.5 s at speed 1.
        const val SCROLL_DEAD_ZONE = 0.07f
        const val SCROLL_GAIN = 2.2f
        const val EDGE_MARGIN = 150f

        // The left bud of one pair of AirPods Pro 3 (the right bud's is mirrored: y and z negated),
        // used only for the moment before the AirPods answer the descriptor request.
        val DEFAULT_HEAD_TO_HEADPHONE = Quaternion(w = 0.396f, x = 0.6312f, y = 0.1628f, z = 0.6466f)
    }
}

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
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.data.apple.Quaternion
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.spatial.HeadPose
import me.kavishdevar.librepods.spatial.MotionUsers
import rikka.shizuku.Shizuku
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/**
 * System-wide spatial audio (roadmap #17): presents the AirPods to Android as a head tracker, so
 * the phone's own spatializer follows the head for every app that plays spatialized audio, the way
 * it does with Pixel Buds Pro.
 *
 * Android ties a head tracker to headphones by the Bluetooth address in the tracker's unique ID,
 * and only accepts head trackers that arrive as HID sensors; an app can't make one, but the shell
 * can (through /dev/uhid), so [ShizukuBridge] runs the virtual device with the shell's identity.
 * This feeds it the head's orientation from [HeadPose], as Android's head frame (X to the right
 * ear, Y out of the nose, Z up) against straight ahead; Android recentres on its own when the head
 * stays still, and whenever the discontinuity count changes. The AirPods' motion streams only
 * while Android reads the sensor, which it does while it spatializes with head tracking on.
 */
object SystemHeadTracker {
    private const val TAG = "SystemHeadTracker"
    private const val PREF = "system_head_tracker"
    private val STREAM_INTERVAL = 20.milliseconds

    enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NOT_ALLOWED, READY }

    data class State(
        val enabled: Boolean = false,
        val shizuku: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
        val airPods: Boolean = false,
        /** The virtual head tracker exists. */
        val created: Boolean = false,
        /** Android lists a head tracker for the current headphones. */
        val androidSees: Boolean = false,
        /** Android has the sensor on; null while it hasn't said either way. */
        val androidReading: Boolean? = null,
        val streaming: Boolean = false,
        val yawDeg: Float = 0f,
        val spatializer: Spatializer = Spatializer(),
        val failed: Boolean = false,
    )

    data class Spatializer(
        val supported: Boolean = false,
        val enabled: Boolean = false,
        val available: Boolean = false,
        val multichannel: Boolean = false,
        val stereo: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var context: Context? = null
    private var findAirPods: () -> AppleDevice? = { null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    private var bridgeFor: AppleDevice? = null
    private var failures = 0
    private var retryAt = 0L
    private var installedCheckedAt = 0L
    private var installed = false

    private var streamingFor: AppleDevice? = null
    private var motionJobs = emptyList<Job>()
    private val pose = HeadPose(TAG, followWhenStill = false)
    @Volatile private var lastSampleAt = 0L
    private var lastTimestamp: Long? = null
    private var lastPose: Quaternion? = null
    private val rate = FloatArray(3)
    private var publishedAt = 0L
    // For testing the conventions: -1 negates that rotation vector component (adb, debug builds).
    @Volatile private var flip = floatArrayOf(1f, 1f, 1f)

    private val binderReceived = Shizuku.OnBinderReceivedListener { wake.trySend(Unit) }
    private val binderDead = Shizuku.OnBinderDeadListener { wake.trySend(Unit) }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> wake.trySend(Unit) }

    /** Called once by the service: [findAirPods] gives the connected AirPods, if any. */
    fun init(context: Context, findAirPods: () -> AppleDevice?) {
        if (loop != null) return
        this.context = context.applicationContext
        this.findAirPods = findAirPods
        val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, false)
        _state.update { it.copy(enabled = enabled) }
        ShizukuBridge.onLost = { wake.trySend(Unit) }
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        loop = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.e(TAG, "tick failed", it) }
                withTimeoutOrNull(if (_state.value.enabled) 1000L else 3000L) { wake.receive() }
            }
        }
    }

    fun setEnabled(on: Boolean) {
        context?.getSharedPreferences("settings", Context.MODE_PRIVATE)?.edit()?.putBoolean(PREF, on)?.apply()
        failures = 0
        retryAt = 0
        _state.update { it.copy(enabled = on, failed = false) }
        wake.trySend(Unit)
    }

    fun requestPermission() = ShizukuBridge.requestPermission()

    /** Makes where the head points now straight ahead. */
    fun recentre() = pose.recentre()

    /** Re-checks everything now (the settings screen calls this when it opens). */
    fun refresh() {
        installedCheckedAt = 0
        wake.trySend(Unit)
    }

    fun shutdown() {
        loop?.cancel()
        loop = null
        scope.launch { stopBridge() }
    }

    private fun tick() {
        val ctx = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - installedCheckedAt > 10_000) {
            installed = ShizukuBridge.installed(ctx)
            installedCheckedAt = now
        }
        val shizuku = when {
            !installed -> ShizukuStatus.NOT_INSTALLED
            !ShizukuBridge.running() -> ShizukuStatus.NOT_RUNNING
            !ShizukuBridge.allowed() -> ShizukuStatus.NOT_ALLOWED
            else -> ShizukuStatus.READY
        }
        val airPods = findAirPods()
        val want = _state.value.enabled && shizuku == ShizukuStatus.READY && airPods != null
        val bridged = bridgeFor
        if (bridged != null && (!want || bridged != airPods || !ShizukuBridge.connected)) {
            Log.i(TAG, if (!ShizukuBridge.connected) "the head tracker went away" else "removing the head tracker")
            stopBridge()
        }
        if (want && bridgeFor == null && now >= retryAt && airPods != null) {
            if (ShizukuBridge.start(ctx, airPods.macAddress.value)) {
                bridgeFor = airPods
                failures = 0
                pose.recentre()
            } else {
                failures++
                retryAt = now + (5_000L shl failures.coerceAtMost(4)).coerceAtMost(60_000L)
                Log.w(TAG, "couldn't create the head tracker (attempt $failures)")
            }
        }
        val host = if (bridgeFor != null) ShizukuBridge.hostState() else -1
        val reading = if (host < 0) null else (host and 3) == 3
        // Stream while Android reads the sensor, or always if it never says.
        val device = bridgeFor
        if (device != null && reading != false) startStreaming(device) else stopStreaming()
        val spatializer = spatializer(ctx)
        _state.update {
            it.copy(
                shizuku = shizuku,
                airPods = airPods != null,
                created = bridgeFor != null,
                androidSees = bridgeFor != null && spatializer.second,
                androidReading = reading,
                streaming = streamingFor != null,
                spatializer = spatializer.first,
                failed = failures > 0,
            )
        }
    }

    /** Android's spatializer, and whether it has a head tracker for the current headphones. */
    private fun spatializer(ctx: Context): Pair<Spatializer, Boolean> = runCatching {
        val sp = ctx.getSystemService(AudioManager::class.java).spatializer
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        fun format(mask: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48_000).setChannelMask(mask).build()
        Spatializer(
            supported = sp.immersiveAudioLevel != android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE,
            enabled = sp.isEnabled,
            available = sp.isAvailable,
            multichannel = sp.canBeSpatialized(attrs, format(AudioFormat.CHANNEL_OUT_5POINT1)),
            stereo = sp.canBeSpatialized(attrs, format(AudioFormat.CHANNEL_OUT_STEREO)),
        ) to sp.isHeadTrackerAvailable
    }.getOrElse { Spatializer() to false }

    private fun stopBridge() {
        stopStreaming()
        context?.let { ShizukuBridge.shutdown(it) }
        bridgeFor = null
    }

    // ---- Motion -------------------------------------------------------------------------------

    private fun startStreaming(device: AppleDevice) {
        if (streamingFor == device) return
        stopStreaming()
        streamingFor = device
        MotionUsers.add(TAG)
        pose.restart()
        lastTimestamp = null
        lastPose = null
        rate.fill(0f)
        lastSampleAt = SystemClock.elapsedRealtime()
        motionJobs = listOf(
            scope.launch { device.motionSamples.collect { onSample(device, it) } },
            scope.launch {
                startStream(device)
                // The stream stops when the bud carrying it leaves an ear; restart it.
                while (isActive) {
                    delay(1_000)
                    if (SystemClock.elapsedRealtime() - lastSampleAt > 2_500) {
                        Log.i(TAG, "no motion for a while, restarting the stream")
                        startStream(device)
                    }
                }
            },
        )
        Log.i(TAG, "following the head for Android")
    }

    private fun stopStreaming() {
        val device = streamingFor ?: return
        motionJobs.forEach { it.cancel() }
        motionJobs = emptyList()
        streamingFor = null
        MotionUsers.release(TAG, device)
        Log.i(TAG, "stopped following the head")
    }

    private suspend fun startStream(device: AppleDevice) {
        device.aacp.requestSensorDescriptors()
        delay(600)
        device.setMotionStream(STREAM_INTERVAL)
        lastSampleAt = SystemClock.elapsedRealtime()
    }

    private fun onSample(device: AppleDevice, sample: MotionSample) {
        lastSampleAt = SystemClock.elapsedRealtime()
        val previous = lastTimestamp
        lastTimestamp = sample.timestampMicros
        if (!pose.update(device, sample)) return
        // The head against straight ahead in Android's head frame: turn (about Z, positive to the
        // left), then look up (about X), then tilt (about Y, positive with the right ear down).
        val q = axis(2, -pose.yaw) * axis(0, pose.pitch) * axis(1, pose.roll)
        val rv = rotationVector(q)
        // Angular velocity in the head frame, from the change since the last sample.
        val last = lastPose
        lastPose = q
        if (last != null && previous != null) {
            var elapsed = sample.timestampMicros - previous
            if (elapsed < 0) elapsed += 1L shl 32
            val dt = elapsed / 1_000_000f
            if (dt in 0.001f..0.2f) {
                val w = rotationVector(last.conjugate() * q)
                for (i in 0..2) rate[i] += (w[i] / dt - rate[i]) * 0.5f
            }
        }
        val f = flip
        ShizukuBridge.sendPose(
            scale(rv[0] * f[0], PI.toFloat()), scale(rv[1] * f[1], PI.toFloat()), scale(rv[2] * f[2], PI.toFloat()),
            scale(rate[0] * f[0], 32f), scale(rate[1] * f[1], 32f), scale(rate[2] * f[2], 32f),
            pose.anchors and 0xFF,
        )
        val now = SystemClock.elapsedRealtime()
        if (now - publishedAt > 100) {
            publishedAt = now
            _state.update { it.copy(yawDeg = Math.toDegrees(pose.yaw.toDouble()).toFloat()) }
        }
    }

    private fun axis(i: Int, angle: Float): Quaternion {
        val s = sin(angle / 2)
        val c = cos(angle / 2)
        return when (i) {
            0 -> Quaternion(c, s, 0f, 0f)
            1 -> Quaternion(c, 0f, s, 0f)
            else -> Quaternion(c, 0f, 0f, s)
        }
    }

    /** Axis times angle (radians), the shorter way round. */
    private fun rotationVector(q: Quaternion): FloatArray {
        val sign = if (q.w < 0f) -1f else 1f
        val x = q.x * sign
        val y = q.y * sign
        val z = q.z * sign
        val s = sqrt(x * x + y * y + z * z)
        val k = if (s > 1e-6f) 2f * atan2(s, q.w * sign) / s else 2f
        return floatArrayOf(x * k, y * k, z * k)
    }

    private fun scale(v: Float, max: Float) = (v / max * 32767f).roundToInt().coerceIn(-32767, 32767)

    /**
     * adb (debug builds): `--es flip xz` negates those rotation vector components ("" for none),
     * `--ez recentre true` recentres. Logs the state either way.
     */
    fun debug(intent: Intent) {
        intent.getStringExtra("flip")?.let { axes ->
            flip = floatArrayOf(if ('x' in axes) -1f else 1f, if ('y' in axes) -1f else 1f, if ('z' in axes) -1f else 1f)
            Log.i(TAG, "flipping ${axes.ifEmpty { "nothing" }}")
        }
        if (intent.getBooleanExtra("recentre", false)) recentre()
        wake.trySend(Unit)
        Log.i(TAG, "state: ${_state.value}, host state ${ShizukuBridge.hostState()}")
    }
}

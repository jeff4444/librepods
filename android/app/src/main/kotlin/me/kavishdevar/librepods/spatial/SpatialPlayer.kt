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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.devices.AppleDevice
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * Head-tracked spatial audio in one app (roadmap #16): plays a song as two virtual speakers in
 * front of the listener that stay put in the room while the head turns.
 *
 * Head direction comes from the AirPods' own fused orientation (gyro plus gravity), turned into
 * the head frame with the bud's head-to-headphone transform (roadmap #9): the heading of the head's
 * forward axis against where it pointed at Recentre, and its elevation against gravity. Nothing is
 * integrated here, so error doesn't build up with movement. The heading is predicted ahead by the
 * Bluetooth delay from the gyro, and the reference follows the head very slowly once it has been
 * still a while, since the AirPods have no compass and their heading drifts.
 */
object SpatialPlayer {
    private const val TAG = "SpatialPlayer"
    private const val BLOCK_MS = 10
    private val STREAM_INTERVAL = 20.milliseconds

    data class State(
        val title: String? = null,
        val playing: Boolean = false,
        val tracking: Boolean = true,
        val headTracked: Boolean = false,
        val yawDeg: Float = 0f,
        val pitchDeg: Float = 0f,
        val spreadDeg: Float = 30f,
        val lookaheadMs: Int = 120,
        /**
         * How much of the virtual room (reflections and reverb) to hear, 0..1 of [ROOM_MAX]: more
         * than that sounded unnatural in listening tests (8 Nov 2026), so the slider stops there.
         */
        val room: Float = 0.8f,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the service: the connected AirPods, if any. */
    var findAirPods: () -> AppleDevice? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playback: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    private var uri: Uri? = null
    private var context: Context? = null

    // Where the head points (yaw positive turning right, pitch positive looking up).
    private val pose = HeadPose(TAG, followWhenStill = true)
    private var motionJobs = emptyList<Job>()
    private var device: AppleDevice? = null
    @Volatile private var lastSampleAt = 0L

    // ---- Controls ----------------------------------------------------------------------------

    fun open(context: Context, uri: Uri) {
        stop()
        this.context = context.applicationContext
        this.uri = uri
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
        _state.value = _state.value.copy(title = title, error = null, positionMs = 0, durationMs = 0)
        play()
    }

    fun play() {
        val ctx = context ?: return
        val u = uri ?: return
        if (playback != null) {
            paused = false
            _state.value = _state.value.copy(playing = true)
            updateTracking()
            return
        }
        stopRequested = false
        paused = false
        _state.value = _state.value.copy(playing = true, error = null)
        playback = Thread({ run(ctx, u) }, "SpatialPlayer").also { it.start() }
        updateTracking()
    }

    fun pause() {
        paused = true
        _state.value = _state.value.copy(playing = false)
        updateTracking()
    }

    fun stop() {
        stopRequested = true
        playback?.join(1000)
        playback = null
        _state.value = _state.value.copy(playing = false)
        updateTracking()
    }

    fun setTracking(on: Boolean) {
        _state.value = _state.value.copy(tracking = on)
        if (!on) recentre()
        updateTracking()
    }

    fun setSpread(deg: Float) { _state.value = _state.value.copy(spreadDeg = deg) }

    fun setLookahead(ms: Int) { _state.value = _state.value.copy(lookaheadMs = ms) }

    fun setRoom(amount: Float) { _state.value = _state.value.copy(room = amount.coerceIn(0f, 1f)) }

    fun recentre() = pose.recentre()

    // ---- Head tracking -----------------------------------------------------------------------

    @Synchronized
    private fun updateTracking() {
        val want = _state.value.playing && _state.value.tracking
        val airPods = if (want) findAirPods() else null
        if (airPods != null && airPods == device) return
        motionJobs.forEach { it.cancel() }
        motionJobs = emptyList()
        device?.let { old -> MotionUsers.release(TAG, old) }
        device = airPods
        _state.value = _state.value.copy(headTracked = airPods != null)
        if (airPods == null) return
        MotionUsers.add(TAG)
        pose.restart()
        lastSampleAt = SystemClock.elapsedRealtime()
        motionJobs = listOf(
            scope.launch { airPods.motionSamples.collect { onSample(airPods, it) } },
            scope.launch {
                startStream(airPods)
                // The stream stops when the bud carrying it leaves an ear; restart it.
                while (isActive) {
                    delay(1_000)
                    if (SystemClock.elapsedRealtime() - lastSampleAt > 2_500) {
                        Log.i(TAG, "no motion for a while, restarting the stream")
                        startStream(airPods)
                    }
                }
            },
        )
        Log.i(TAG, "head tracking on")
    }

    private suspend fun startStream(device: AppleDevice) {
        device.aacp.requestSensorDescriptors()
        delay(600)
        device.setMotionStream(STREAM_INTERVAL)
        lastSampleAt = SystemClock.elapsedRealtime()
    }

    private fun onSample(device: AppleDevice, sample: MotionSample) {
        lastSampleAt = SystemClock.elapsedRealtime()
        if (!pose.update(device, sample)) return
        _state.value = _state.value.copy(
            yawDeg = Math.toDegrees(pose.yaw.toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(pose.pitch.toDouble()).toFloat(),
        )
    }

    // ---- Playback ----------------------------------------------------------------------------

    private fun run(context: Context, uri: Uri) {
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change -> if (change <= 0) pause() }
            .build()
        audio.requestAudioFocus(focus)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio in this file")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            _state.value = _state.value.copy(durationMs = durationUs / 1000)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var renderer: BinauralRenderer? = null
            var room: Room? = null
            var block = 0
            // Decoded audio waiting to be rendered, deinterleaved.
            var fifoL = FloatArray(1 shl 16)
            var fifoR = FloatArray(1 shl 16)
            var fifoLen = 0
            var written = 0L
            val dirs = Array(2) { FloatArray(3) }
            var outL = FloatArray(0)
            var outR = FloatArray(0)
            var inL = FloatArray(0)
            var inR = FloatArray(0)
            var interleaved = FloatArray(0)
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var cpuNs = 0L
            var renderedFrames = 0L

            fun setup() {
                renderer = BinauralRenderer(ShHrir.load(context, sampleRate))
                room = Room(sampleRate)
                block = sampleRate * BLOCK_MS / 1000
                outL = FloatArray(block); outR = FloatArray(block); inL = FloatArray(block); inR = FloatArray(block)
                interleaved = FloatArray(block * 2)
                track?.release()
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(max(minBuf, block * 8 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build().also { it.play() }
                Log.i(TAG, "playing at $sampleRate Hz, $channels channels, blocks of $block frames")
            }
            setup()

            while (!stopRequested && !outputDone) {
                if (paused) {
                    track?.pause()
                    while (paused && !stopRequested) Thread.sleep(50)
                    if (stopRequested) break
                    track?.play()
                }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (rate != sampleRate) { sampleRate = rate; setup() }
                    }
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }
                        val frames = info.size / (channels * if (float) 4 else 2)
                        if (fifoLen + frames > fifoL.size) {
                            val n = Integer.highestOneBit(fifoLen + frames) * 2
                            fifoL = fifoL.copyOf(n); fifoR = fifoR.copyOf(n)
                        }
                        buf.position(info.offset)
                        for (i in 0 until frames) {
                            var l = 0f
                            var r = 0f
                            for (c in 0 until channels) {
                                val v = if (float) buf.getFloat() else buf.getShort() / 32768f
                                if (c == 0) l = v else if (c == 1) r = v
                            }
                            if (channels == 1) r = l
                            fifoL[fifoLen + i] = l
                            fifoR[fifoLen + i] = r
                        }
                        fifoLen += frames
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
                // Render whole blocks.
                while (fifoLen >= block && !stopRequested) {
                    val s = _state.value
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val headYaw = if (s.tracking) pose.yaw + pose.yawRate * s.lookaheadMs / 1000f else 0f
                    val headPitch = if (s.tracking) pose.pitch else 0f
                    BinauralRenderer.headRelative(s.spreadDeg, headYaw, headPitch, dirs[0])
                    BinauralRenderer.headRelative(-s.spreadDeg, headYaw, headPitch, dirs[1])
                    fifoL.copyInto(inL, 0, 0, block)
                    fifoR.copyInto(inR, 0, 0, block)
                    fifoL.copyInto(fifoL, 0, block, fifoLen)
                    fifoR.copyInto(fifoR, 0, block, fifoLen)
                    fifoLen -= block
                    outL.fill(0f); outR.fill(0f)
                    renderer!!.process(inL, inR, block, dirs, outL, outR)
                    val roomAmount = s.room * ROOM_MAX
                    room!!.process(inL, inR, block, headYaw, headPitch, s.spreadDeg, roomAmount, outL, outR)
                    // The room adds energy; trim so loud songs don't clip.
                    val gain = GAIN / (1f + 0.6f * roomAmount)
                    for (i in 0 until block) {
                        interleaved[2 * i] = (outL[i] * gain).coerceIn(-1f, 1f)
                        interleaved[2 * i + 1] = (outR[i] * gain).coerceIn(-1f, 1f)
                    }
                    cpuNs += SystemClock.elapsedRealtimeNanos() - t0
                    renderedFrames += block
                    track!!.write(interleaved, 0, block * 2, AudioTrack.WRITE_BLOCKING)
                    written += block
                    if (written % (sampleRate * 5L) < block) {
                        Log.i(TAG, "rendering takes ${"%.1f".format(cpuNs / 1e6 / (renderedFrames * 1000.0 / sampleRate) * 100)}% of real time; yaw ${"%.0f".format(Math.toDegrees(pose.yaw.toDouble()))}")
                        cpuNs = 0; renderedFrames = 0
                    }
                    _state.value = _state.value.copy(positionMs = written * 1000 / sampleRate)
                }
            }
            if (!stopRequested) Log.i(TAG, "finished")
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { track?.stop() }
            track?.release()
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
            audio.abandonAudioFocusRequest(focus)
            playback = null
            _state.value = _state.value.copy(playing = false)
            scope.launch { updateTracking() }
        }
    }

    // The two speakers summed through the HRIRs come out a little hotter than the original.
    private const val GAIN = 0.7f

    // The room level at the slider's top: past this it stopped sounding like a real room.
    private const val ROOM_MAX = 0.5f
}

package me.kavishdevar.librepods.core.devices

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.ATTManager
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionSample
import me.kavishdevar.librepods.core.data.apple.Quaternion
import me.kavishdevar.librepods.core.data.audio.MicrophoneFrame
import me.kavishdevar.librepods.core.data.audio.MicrophoneState
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.data.recording.RecordingState
import me.kavishdevar.librepods.core.utils.GestureDetector
import me.kavishdevar.librepods.core.utils.HeadTracking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AppleDevice"

@SuppressLint("MissingPermission")
class AppleDevice(
    override val context: Context,
    override val bluetoothAdapter: BluetoothAdapter,
    override val bluetoothDevice: BluetoothDevice,
    currentState: ConnectionState
) : Device<AppleState, AppleSettings, AppleMetadata> {
    override val macAddress = MacAddress(bluetoothDevice.address)
    private val _state = MutableStateFlow(AppleState())
    override val state = _state.asStateFlow()

    private val _settings = MutableStateFlow(AppleSettings())
    override val settings = _settings.asStateFlow()

    private val _metadata = MutableStateFlow(AppleMetadata())
    override val metadata = _metadata.asStateFlow()

    private val _connectionState = MutableStateFlow(currentState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<AppleEvent>()
    val events = _events.asSharedFlow()

    private val _microphoneFrames = MutableSharedFlow<MicrophoneFrame>(
        extraBufferCapacity = 64
    )
    val microphoneFrames = _microphoneFrames.asSharedFlow()

    // Head motion decoded from the DEVMOTION6 stream, for features such as the head mouse.
    private val _motionSamples = MutableSharedFlow<MotionSample>(extraBufferCapacity = 64)
    val motionSamples = _motionSamples.asSharedFlow()

    internal fun emitMotionSample(sample: MotionSample) {
        _motionSamples.tryEmit(sample)
    }

    // Rotation from the bud's motion frame to the head frame (yaw about Z, pitch about X), sent in
    // the motion stream's descriptor on every connection as "H2HTransformation".
    private val _headToHeadphone = MutableStateFlow<Quaternion?>(null)
    val headToHeadphone: StateFlow<Quaternion?> = _headToHeadphone.asStateFlow()

    internal fun setHeadToHeadphone(q: Quaternion) {
        _headToHeadphone.value = q
    }

    private val _connectionNumber = MutableStateFlow(0)
    override val connectionNumber = _connectionNumber.asStateFlow()

    fun loadInitialState(state: AppleState, settings: AppleSettings, metadata: AppleMetadata) {
        _state.value = state
        _settings.value = settings
        _metadata.value = metadata
    }

    // exists because audio processing is done by Service and other parts use Device as source of truth for microphone state and I didn't want to make whole updateState public
    fun updateMicrophoneState(
        transform: (MicrophoneState) -> MicrophoneState
    ) {
        _state.update {
            it.copy(
                microphoneState = transform(it.microphoneState)
            )
        }
    }

    // should use applestate.state which gives the actual state from proximity instead of creating our own ConnectionSTate.AVAILABLE... well
    internal fun updateConnectionState(
        connectionState: ConnectionState
    ) = _connectionState.update { connectionState }

    internal inline fun updateState(
        transform: (AppleState) -> AppleState
    ) {
        _state.update(transform)
    }

    internal inline fun updateMetadata(
        transform: (AppleMetadata) -> AppleMetadata
    ) {
        _metadata.update(transform)
    }

    fun updateSettings(
        transform: (AppleSettings) -> AppleSettings
    ) {
        _settings.update(transform)
    }

    internal suspend fun emitEvent(event: AppleEvent) {
        _events.emit(event)
    }

    internal suspend fun emitMicrophoneFrame(frame: MicrophoneFrame) {
        _microphoneFrames.emit(frame)
    }

    val aacp = AACPManager(this)
    val att = ATTManager(this)


    // ik, probably wrong place. TODO
    private val gestureDetector by lazy {
        GestureDetector()
    }

    init {
        updateMetadata {
            it.copy(
                name = bluetoothDevice.alias ?: bluetoothDevice.name ?: "Unknown"
            )
        }
        // The service connects once the saved settings are loaded, since they say whether the user
        // disconnected these AirPods (stayDisconnected).
    }

    override fun connect(): Boolean {
        _connectionState.update {
            ConnectionState.CONNECTING
        }

        val success = aacp.connect() // && att.connect()

        CoroutineScope(Dispatchers.IO).launch {
            _state.update {
                it.copy(
                    loudSoundReductionEnabled = readATTCharacteristic(ATTHandle.LOUD_SOUND_REDUCTION)?.getOrNull(0)?.toInt() == 1,
                    transparencyData = readATTCharacteristic(ATTHandle.TRANSPARENCY)?: byteArrayOf(),
                    hearingAidData = readATTCharacteristic(ATTHandle.HEARING_AID)?: byteArrayOf()
                )
            }
        }

        _connectionState.update {
            if (success) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
        }
        _connectionNumber.update {
            it + 1
        }

        return success
    }

    override val heldDisconnected: Boolean
        get() = _settings.value.stayDisconnected

    // When LibrePods last took the audio down itself (elapsedRealtime), so the service can tell
    // those disconnects from Android's own.
    @Volatile var audioDisconnectedByUsAt = 0L
        private set

    override fun disconnectAudio() {
        audioDisconnectedByUsAt = SystemClock.elapsedRealtime()
        super.disconnectAudio()
    }

    override fun disconnectByUser() {
        Log.i(TAG, "disconnected by the user; holding off reconnecting until they connect again")
        _settings.update { it.copy(stayDisconnected = true) }
        // bluetoothDevice.disconnect() in disconnect() is refused without a companion device
        // association, so take the audio down here; with the control link closed too, nothing
        // holds the connection open and it drops.
        disconnectAudio()
        disconnect()
    }

    override fun connectByUser(): Boolean {
        if (_settings.value.stayDisconnected) Log.i(TAG, "connect asked for by the user, lifting the hold")
        _settings.update { it.copy(stayDisconnected = false) }
        val connected = connect()
        // A disconnect takes the audio down too (bluetoothDevice.disconnect below), so ask Android
        // to bring the audio back with the control link.
        if (connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            try {
                bluetoothDevice.connect()
            } catch (e: Exception) {
                // Refused without a companion device association; the AirPods bring the audio up
                // themselves once the control link connects.
                Log.w(TAG, "couldn't connect the audio through Android: ${e.message}")
            }
        }
        return connected
    }

    override fun disconnect() {
        _state.update {
            it.copy(
                lastDisconnected = System.currentTimeMillis()
            )
        }
        _connectionState.update {
            ConnectionState.DISCONNECTING
        }

        aacp.disconnect()
//        att.disconnect()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            try {
                bluetoothDevice.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "couldn't disconnect bluetooth device: ${e.message}")
            }
        }

        _connectionState.update {
            ConnectionState.DISCONNECTED
        }
    }

    fun setControlCommand(identifier: ControlCommandIdentifier, value: ByteArray): Boolean = aacp.sendControlCommand(identifier.value, value)
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Byte): Boolean = aacp.sendControlCommand(identifier.value, value)
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Int): Boolean = aacp.sendControlCommand(identifier.value, value)
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Boolean): Boolean = aacp.sendControlCommand(identifier.value, value)

    suspend fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray): Boolean {
        val success = att.writeCharacteristic(handle, value)
        if (success) {
            when (handle) {
                ATTHandle.LOUD_SOUND_REDUCTION -> _state.update { it.copy(loudSoundReductionEnabled = value.getOrNull(0)?.toInt() == 1) }
                ATTHandle.TRANSPARENCY -> _state.update { it.copy(transparencyData = value) }
                ATTHandle.HEARING_AID -> _state.update { it.copy(hearingAidData = value) }
            }
        }
        return success
    }

    // can't use att notifications; keeping ATT connected causes airpods to disconnect every few seconds for some reason
    // we will poll the characteristic every 1 seconds instead.
    fun observeATTCharacteristic(handle: ATTHandle): Job = CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            val value = readATTCharacteristic(handle)
            if (value != null) {
                when (handle) {
                    ATTHandle.LOUD_SOUND_REDUCTION -> _state.update { it.copy(loudSoundReductionEnabled = value.getOrNull(0)?.toInt() == 1) }
                    ATTHandle.TRANSPARENCY -> _state.update { it.copy(transparencyData = value) }
                    ATTHandle.HEARING_AID -> _state.update { it.copy(hearingAidData = value) }
                }
            }
            delay(1000.milliseconds)
        }
    }

    suspend fun readATTCharacteristic(handle: ATTHandle): ByteArray? = att.readCharacteristic(handle)

    fun requestMicrophoneStream() = aacp.requestMicrophoneStream()
    fun endMicrophoneStream() {
        aacp.endMicrophoneStream()
        reconnectA2dp()
    }

    fun startRecording(recording: Recording) {
        requestMicrophoneStream()
        updateState {
            it.copy(
                recordingState = RecordingState(
                    isRecording = true,
                    recording = recording
                )
            )
        }
    }
    fun stopRecording() {
        endMicrophoneStream()
        updateState {
            it.copy(
                recordingState = RecordingState(
                    isRecording = false,
                    recording = null
                ),
                microphoneState = MicrophoneState(),
            )
        }
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommand(ControlCommandIdentifier.LISTENING_MODE_CONFIGS, newValue)
    }

    fun setStemAction(bud: StemPressBud, type: StemPressType, action: StemAction) {
        _settings.update { it.withStemAction(bud, type, action) }
    }

    /**
     * Tells the AirPods which press types to forward to the phone. Forwarded presses are no longer
     * handled by the AirPods, so the app must act on every one of them. With [onCall] nothing is
     * forwarded, so answering and hanging up work as usual.
     */
    fun applyStemPressForwarding(onCall: Boolean = false, extra: Set<StemPressType> = emptySet()): Boolean {
        val forwarded = if (onCall) emptySet() else settings.value.forwardedPressTypes() + extra
        return aacp.sendRawGesturesConfig(
            singlePressCustomized = StemPressType.SINGLE_PRESS in forwarded,
            doublePressCustomized = StemPressType.DOUBLE_PRESS in forwarded,
            triplePressCustomized = StemPressType.TRIPLE_PRESS in forwarded,
            longPressCustomized = StemPressType.LONG_PRESS in forwarded,
        )
    }

    fun setLongPressAction(side: String, action: StemAction) {
        _settings.update {
            if (side.lowercase() == "left") it.copy(leftLongPressAction = action) else it.copy(rightLongPressAction = action)
        }
    }

    fun renameDevice(newName: String) {
        aacp.sendRename(newName)
        _metadata.update {
            it.copy(name = newName)
        }
    }

    fun startHeadTracking() {
        val sensorServiceType = when (metadata.value.model) {
            AirPodsModel.AIRPODS_5,
            AirPodsModel.AIRPODS_5_WCC -> {
                SensorServiceType.CMA_COMMAND
            }
            else -> {
                if (metadata.value.version3.ifBlank { "8" }.first().digitToInt() >= 8) SensorServiceType.DEVMOTION6 else SensorServiceType.ACTIVITY
            }
        }
        aacp.setSensorServiceReportInterval(
            sensorServiceType = sensorServiceType,
            interval = _settings.value.headTrackingInterval
        )
        _state.update {
            it.copy(
                headTrackingState = BuddyState.WAITING
            )
        }
    }

    /**
     * Starts (or with a zero [interval] stops) the head-motion stream decoded into [motionSamples],
     * using the service this model and firmware stream motion on.
     */
    fun setMotionStream(interval: Duration): Boolean {
        val sensorServiceType = when (metadata.value.model) {
            AirPodsModel.AIRPODS_5,
            AirPodsModel.AIRPODS_5_WCC -> if (interval == Duration.ZERO) SensorServiceType.DEVMOTION6 else SensorServiceType.CMA_COMMAND
            else -> if (metadata.value.version3.ifBlank { "8" }.first().digitToInt() >= 8) SensorServiceType.DEVMOTION6 else SensorServiceType.ACTIVITY
        }
        return aacp.setSensorServiceReportInterval(sensorServiceType = sensorServiceType, interval = interval)
    }

    fun stopHeadTracking() {
        val sensorServiceType = when (metadata.value.model) {
            AirPodsModel.AIRPODS_5,
            AirPodsModel.AIRPODS_5_WCC -> {
                SensorServiceType.DEVMOTION6
            }
            else -> {
                if (metadata.value.version3.ifBlank { "8" }.first().digitToInt() >= 8) SensorServiceType.DEVMOTION6 else SensorServiceType.ACTIVITY
            }
        }
        aacp.setSensorServiceReportInterval(
            sensorServiceType = sensorServiceType,
            interval = Duration.ZERO
        )
        _state.update {
            it.copy(
                headTrackingState = BuddyState.INACTIVE,
            )
        }
        gestureDetector.stopDetection()
    }

    // TODO: multiple callbacks
    fun detectHeadGestures(callback: (Boolean) -> Unit) {
        if (state.value.headTrackingState != BuddyState.ACTIVE) startHeadTracking()
        val started = gestureDetector.startDetection(HeadTracking.acceleration, callback)
        if (!started) {
            Log.w(TAG, "Failed to start gesture detection, probably because already running.")
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        _settings.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setHeadGesturesVerticalOffset(offset: Int) {
        _settings.update {
            it.copy(headGesturesVerticalOffset = offset)
        }
    }

    fun setHeadGesturesHorizontalOffset(offset: Int) {
        _settings.update {
            it.copy(headGesturesHorizontalOffset = offset)
        }
    }

    fun setHeadTrackingInterval(interval: Duration) {
        startHeadTracking()
        _settings.update {
            it.copy(headTrackingInterval = interval)
        }
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        aacp.setCustomEq(_state.value.customEq.copy(state = if(enabled) 2 else 1))
    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        aacp.setCustomEq(_state.value.customEq.copy(low = low, mid = mid, high = high))
    }

    // AACPManager sets hrmActive true when a valid reading is received
    fun startHr(): Boolean {
        val success = aacp.setSensorServiceReportInterval(
            sensorServiceType = if (metadata.value.version3.ifBlank { "8" }.first().digitToInt() >= 9) SensorServiceType.HEARTRATE_COMMAND else SensorServiceType.HEARTRATE,
            interval = 1.seconds
        )
        if (success) {
            _state.update {
                it.copy(
                    hrmState = BuddyState.WAITING
                )
            }
        }
        return success
    }
    fun stopHr(): Boolean {
        val success = aacp.setSensorServiceReportInterval(
            sensorServiceType = if (metadata.value.version3.ifBlank { "8" }.first().digitToInt() >= 9) SensorServiceType.HEARTRATE_COMMAND else SensorServiceType.HEARTRATE,
            interval = Duration.ZERO
        )
        if (state.value.hrmState != BuddyState.INACTIVE && success) _state.update {
            it.copy(
                hrmState = BuddyState.INACTIVE,
                currentHeartRate = null
            )
        }
        return success
    }

    fun sendRawPacket(data: ByteArray): Boolean = aacp.sendRawPacket(data)

////    private val _cameraAction = MutableStateFlow(
////        sharedPreferences.getString("camera_action", null)
////            ?.let { value -> StemPressType.entries.find { it.name == value } })
////
////    val cameraAction: StateFlow<StemPressType?> = _cameraAction
////
////    fun setCameraAction(action: StemPressType?) {
////        sharedPreferences.edit {
////            if (action == null) remove("camera_action")
////            else putString("camera_action", action.name)
////        }
////        _cameraAction.value = action
////    }

}

private fun countEnabledModes(byteValue: Int): Int {
    var count = 0
    if ((byteValue and 0x01) != 0) count++
    if ((byteValue and 0x02) != 0) count++
    if ((byteValue and 0x04) != 0) count++
    if ((byteValue and 0x08) != 0) count++
    return count
}

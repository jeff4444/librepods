package me.kavishdevar.librepods.core.devices

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.maestro.MaestroClient
import me.kavishdevar.librepods.core.bluetooth.maestro.MaestroClient.Peer
import me.kavishdevar.librepods.core.bluetooth.maestro.Proto
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10

private const val TAG = "PixelBuds"

/** Noise control modes as the buds number them (Maestro setting 13). */
enum class PixelBudsNoiseMode(val value: Int) {
    OFF(1), NOISE_CANCELLATION(2), TRANSPARENCY(3), ADAPTIVE(4);

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.value == value }
    }
}

/**
 * What a press and hold does (Maestro's RegularActionTarget). Google's app only offers noise
 * control and the assistant on the Pro 2; the rest are the firmware's own list, and 7 was seen on
 * these buds (likely Gemini).
 */
object PixelBudsHoldAction {
    const val CHECK_NOTIFICATIONS = 1
    const val PREVIOUS_TRACK = 2
    const val NEXT_TRACK = 3
    const val PLAY_PAUSE = 4
    const val NOISE_CONTROL = 5
    const val ASSISTANT = 6
    const val ACTION_7 = 7

    val ALL = listOf(NOISE_CONTROL, ASSISTANT, ACTION_7, PLAY_PAUSE, NEXT_TRACK, PREVIOUS_TRACK, CHECK_NOTIFICATIONS)
}

/** Maestro setting ids (pbpctrl's SettingValue field numbers, plus what captures showed). */
object PixelBudsSetting {
    const val IN_EAR_DETECTION = 2
    const val GESTURES = 4
    const val DIAGNOSTICS = 5
    const val HOLD_ACTIONS = 7
    const val MULTIPOINT = 11
    const val NOISE_LOOP = 12
    const val NOISE_MODE = 13
    /** "OTTS" mode: on while the ear tip fit test runs. */
    const val TEST_MODE = 14
    const val VOLUME_EQ = 15
    const val EQ = 16
    const val BALANCE = 17
    const val MONO = 19
    const val VOLUME_EXPOSURE_NOTIFICATIONS = 21
    const val CONVERSATION_DETECTION = 22
    // Named from Google's app (its settings handlers and their logs), 2026-11-08:
    const val SETUP_FINISHED = 3
    const val SETUP_MODE = 6
    const val LAST_SAVED_EQ = 18
    /** Chimes from the case other than a bud going back in. */
    const val CASE_SOUNDS = 27
    /** The chime when a bud goes back in the case. */
    const val BUD_RETURN_SOUND = 28
    /** Nod / shake gestures: 1 off, 2 on. */
    const val HEAD_GESTURES = 29
    const val PHONE_BOOTH = 34
    const val SLEEP_DETECTION = 36
    /** Live Translate's settings (a message; has an auto-ANC flag). */
    const val LIVE_TRANSLATE = 37
    /** Muting the call mic with a gesture (Google's app needs a Pixel phone for it). */
    const val MIC_MUTE_GESTURE = 38

    val READ = listOf(NOISE_MODE, CONVERSATION_DETECTION, IN_EAR_DETECTION, MULTIPOINT, EQ, HOLD_ACTIONS,
        NOISE_LOOP, GESTURES, VOLUME_EQ, BALANCE, MONO, VOLUME_EXPOSURE_NOTIFICATIONS, CASE_SOUNDS,
        BUD_RETURN_SOUND, HEAD_GESTURES, PHONE_BOOTH, SLEEP_DETECTION, MIC_MUTE_GESTURE, DIAGNOSTICS)

    /** Ids nobody has named yet: read after the rest and logged, so they can be mapped. */
    val EXPLORE = (1..40).filter { it !in READ && it != TEST_MODE }

    val NAMED = READ + listOf(TEST_MODE, SETUP_FINISHED, SETUP_MODE, LAST_SAVED_EQ, LIVE_TRANSLATE)

    // Unnamed, typed from Google's schema (its SettingValue class): on/off settings...
    val UNKNOWN_SWITCHES = listOf(20, 25, 30, 32, 33, 39)
    // ...three-way ones, the same enum as head gestures (0 not set, 1 off, 2 on)...
    val UNKNOWN_TRISTATE = listOf(24, 26)
    // ...and messages whose fields Google's app doesn't read (its build stripped them).
    /** Shaped like one bud's press-and-hold entry: {4: {1: action}}. */
    const val UNKNOWN_GESTURE_SLOT = 23
    /** Two floats and a number: {1: float, 2: float, 3: int}. */
    const val UNKNOWN_FLOATS_AND_LEVEL = 31
    const val UNKNOWN_MESSAGE = 35

    const val EQ_MIN = -6f
    const val EQ_MAX = 6f

    /** Balance as the buds store it (0..200, even leans left, odd right) to -100 (left) .. 100 (right). */
    fun balanceFromRaw(raw: Int): Int = if (raw and 1 != 0) (raw shr 1) + 1 else -(raw shr 1)
    fun balanceToRaw(value: Int): Int = value.coerceIn(-100, 100).let { if (it > 0) ((it - 1) shl 1) or 1 else (-it) shl 1 }
}

/**
 * A head gesture report: [gesture] once one is recognised (1 nod, 2 shake, as Google's own
 * tutorial reads them), and while moving, the [axis] being swung (1 nod, 2 shake) and how many
 * swings so far. A gesture completes at 4 swings. Only the right bud detects.
 */
data class PixelBudsHeadGesture(val gesture: Int?, val axis: Int?, val swings: Int?) {
    companion object {
        const val NOD = 1
        const val SHAKE = 2
    }
}

/** One bud's ear tip fit result: lower [fit] is a better seal (tentative), [noise] the room's noise. */
data class PixelBudsFitResult(val fit: Float, val noise: Float) {
    enum class Grade { GOOD, ADJUST, CHANGE_TIPS, NOISY, FAILED }

    // Thresholds from Google's app flags: fit good at 80, max 200; noise max 40 (Pro 2); 255 = no reading.
    val grade: Grade
        get() = when {
            fit >= 255f || noise >= 255f -> Grade.FAILED
            noise > 40f -> Grade.NOISY
            fit <= 80f -> Grade.GOOD
            fit <= 200f -> Grade.ADJUST
            else -> Grade.CHANGE_TIPS
        }
}

data class PixelBudsState(
    val battery: Set<Battery> = emptySet(),
    val leftInCase: Boolean? = null,
    val rightInCase: Boolean? = null,
    /** Setting id to its value as the buds sent it, decoded generically. */
    val settings: Map<Int, Proto.Field> = emptyMap(),
    val lastDisconnected: Long = -1L,
) : DeviceState {
    val noiseMode: PixelBudsNoiseMode? get() = PixelBudsNoiseMode.from(settings[PixelBudsSetting.NOISE_MODE]?.varint?.toInt())
    fun flag(id: Int): Boolean? = settings[id]?.takeIf { it.wireType == 0 }?.let { it.varint != 0L }
    val eq: List<Float>? get() = settings[PixelBudsSetting.EQ]?.bytes?.let(Proto::parse)?.let { m -> (1..5).map { m.float(it) ?: 0f } }
    /** Press-and-hold action per bud (left, right), see [PixelBudsHoldAction]. */
    val holdActions: Pair<Int?, Int?>? get() = settings[PixelBudsSetting.HOLD_ACTIONS]?.bytes?.let(Proto::parse)?.let { m ->
        fun bud(n: Int) = m.message(n)?.message(4)?.int(1)
        bud(1) to bud(2)
    }
    /** Modes a press and hold cycles through when it's set to noise control. */
    val noiseLoop: Set<PixelBudsNoiseMode>? get() = settings[PixelBudsSetting.NOISE_LOOP]?.bytes?.let(Proto::parse)?.let { m ->
        buildSet {
            if (m.bool(1) == true) add(PixelBudsNoiseMode.NOISE_CANCELLATION)
            if (m.bool(2) == true) add(PixelBudsNoiseMode.OFF)
            if (m.bool(3) == true) add(PixelBudsNoiseMode.TRANSPARENCY)
            if (m.bool(4) == true) add(PixelBudsNoiseMode.ADAPTIVE)
        }
    }
    /** -100 (all left) .. 100 (all right). */
    val balance: Int? get() = settings[PixelBudsSetting.BALANCE]?.takeIf { it.wireType == 0 }?.let { PixelBudsSetting.balanceFromRaw(it.varint.toInt()) }
    val bothInCase: Boolean get() = leftInCase == true && rightInCase == true
    val headGestures: Boolean? get() = settings[PixelBudsSetting.HEAD_GESTURES]?.takeIf { it.wireType == 0 }?.let { it.varint == 2L }
}

data class PixelBudsSettings(
    // See Device.heldDisconnected. Saved across restarts (PixelBudsDevice's preferences).
    val stayDisconnected: Boolean = false,
) : DeviceSettings

data class PixelBudsMetadata(
    override val name: String = "",
    /** Google's device type: 6 is Pixel Buds Pro 2 ("largo"). */
    val deviceType: Int? = null,
    /** Colour code: for type 6, 10 Hazel, 6 Porcelain, 12 Wintergreen, 11 Peony, 17 Moonstone. */
    val sku: Int? = null,
    val firmwareLeft: String = "",
    val firmwareRight: String = "",
    val firmwareCase: String = "",
    val serialLeft: String = "",
    val serialRight: String = "",
    val serialCase: String = "",
) : DeviceMetadata {
    override val iconName: String get() = "PixelBudsPro2"

    val modelName: String
        get() = when (deviceType) {
            4, 5 -> "Pixel Buds Pro"
            6 -> "Pixel Buds Pro 2"
            9 -> "Pixel Buds 2a"
            else -> "Pixel Buds"
        }

    val colorName: String?
        get() = if (deviceType == 6) when (sku) {
            10 -> "Hazel"; 6 -> "Porcelain"; 12 -> "Wintergreen"; 11 -> "Peony"; 17 -> "Moonstone"; else -> null
        } else null

    /** Adaptive noise control: Pro 2 and 2a. */
    val hasAdaptive: Boolean get() = deviceType == null || deviceType == 6 || deviceType == 9
}

/**
 * Google Pixel Buds over Maestro (see [MaestroClient]). Android owns the audio and the Bluetooth
 * connection; "connected" here means LibrePods holds the Maestro channel. That fails while
 * Google's Pixel Buds app is installed and holding it, and the device then stays AVAILABLE.
 */
@SuppressLint("MissingPermission")
class PixelBudsDevice(
    override val context: Context,
    override val bluetoothAdapter: BluetoothAdapter,
    override val bluetoothDevice: BluetoothDevice,
    currentState: ConnectionState,
) : Device<PixelBudsState, PixelBudsSettings, PixelBudsMetadata> {
    private companion object {
        const val FIT_TEST = "maestro_pw.EartipFitTest"
        const val DOSIMETER = "maestro_pw.Dosimeter"
        const val HEAD_GESTURE = "maestro_pw.HeadGesture"
        const val MULTIPOINT = "maestro_pw.Multipoint"
        val BUDS = listOf(Peer.LEFT, Peer.RIGHT)
    }

    override val macAddress = MacAddress(bluetoothDevice.address)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maestro = MaestroClient(bluetoothDevice)
    private var peer = Peer.RIGHT

    private val _connectionState = MutableStateFlow(currentState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _state = MutableStateFlow(PixelBudsState())
    override val state = _state.asStateFlow()
    private val _settings = MutableStateFlow(PixelBudsSettings())
    override val settings = _settings.asStateFlow()
    private val _metadata = MutableStateFlow(PixelBudsMetadata(name = bluetoothDevice.alias ?: bluetoothDevice.name ?: "Pixel Buds"))
    override val metadata = _metadata.asStateFlow()
    private val _connectionNumber = MutableStateFlow(0)
    override val connectionNumber = _connectionNumber.asStateFlow()

    /** Live sound level at the buds in dB, while [watchSoundLevel] is on. */
    private val _soundLevel = MutableStateFlow<Float?>(null)
    val soundLevel: StateFlow<Float?> = _soundLevel.asStateFlow()

    /** Touches and on/off-head events the buds report (Maestro's OobeAction numbers). */
    private val _touches = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val touches: SharedFlow<Int> = _touches.asSharedFlow()

    override val heldDisconnected: Boolean get() = _settings.value.stayDisconnected

    /** Blocking: opens Maestro and starts reading. */
    override fun connect(): Boolean {
        if (maestro.isConnected) return true
        _connectionState.value = ConnectionState.CONNECTING
        val ok = maestro.connect(scope) {
            Log.i(TAG, "Maestro closed")
            markDisconnected()
        }
        if (!ok) {
            _connectionState.value = if (isAclConnected()) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
            return false
        }
        _connectionState.value = ConnectionState.CONNECTED
        _connectionNumber.update { it + 1 }
        scope.launch { readAll() }
        return true
    }

    override fun disconnect() {
        maestro.close()
        markDisconnected()
    }

    override fun disconnectByUser() {
        Log.i(TAG, "disconnected by the user; holding off reconnecting until they connect again")
        setHeld(true)
        disconnectAudio()
        disconnect()
    }

    override fun connectByUser(): Boolean {
        Log.i(TAG, "connect asked for by the user${if (heldDisconnected) ", lifting the hold" else ""}")
        setHeld(false)
        // Refused without a companion device association; opening Maestro below brings the
        // connection up anyway.
        if (!isAclConnected()) runCatching { bluetoothDevice.connect() }
        val connected = connect()
        // A disconnect took the audio down too; ask for it back.
        if (connected) connectAudio()
        return connected
    }

    private fun setHeld(held: Boolean) {
        _settings.update { it.copy(stayDisconnected = held) }
        prefs.edit().putBoolean(heldKey, held).apply()
    }

    @Volatile override var audioDisconnectedByUsAt = 0L
        private set

    override fun disconnectAudio() {
        audioDisconnectedByUsAt = SystemClock.elapsedRealtime()
        super.disconnectAudio()
    }

    private fun markDisconnected() {
        _state.update { s ->
            s.copy(
                battery = s.battery.map { it.copy(status = BatteryStatus.DISCONNECTED) }.toSet(),
                // Unknown until the next connection reports them.
                leftInCase = null,
                rightInCase = null,
                lastDisconnected = System.currentTimeMillis(),
            )
        }
        _soundLevel.value = null
        _connectionState.value = if (isAclConnected()) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
    }

    private fun isAclConnected(): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(bluetoothDevice) as Boolean
    }.getOrDefault(false)

    /** Tries the bud that last answered, then the other (one may be in the case or asleep). */
    private suspend fun callEither(method: String, payload: ByteArray = ByteArray(0), service: String = MaestroClient.MAESTRO): MaestroClient.Packet? {
        maestro.call(peer, method, payload, service)?.let { return it }
        val other = if (peer == Peer.RIGHT) Peer.LEFT else Peer.RIGHT
        return maestro.call(other, method, payload, service)?.also { peer = other }
    }

    private suspend fun readAll() {
        // Battery and placement first: they decide between the case card and the island, and the
        // buds are often out of the case a second after the lid opens. Ask both buds, since either
        // may be asleep; they report the same thing.
        for (bud in listOf(Peer.RIGHT, Peer.LEFT)) {
            maestro.subscribe(bud, "SubscribeRuntimeInfo") { payload -> Proto.parse(payload)?.let(::onRuntimeInfo) }
        }
        val software = maestro.call(Peer.RIGHT, "GetSoftwareInfo")?.also { peer = Peer.RIGHT }
            ?: maestro.call(Peer.LEFT, "GetSoftwareInfo")?.also { peer = Peer.LEFT }
        software?.payload?.let(Proto::parse)?.message(4)?.let { fw ->
            // FirmwareInfo: 1 case, 2 right, 3 left; each {1 build, 2 version string}
            _metadata.update {
                it.copy(
                    firmwareCase = fw.message(1)?.string(2).orEmpty(),
                    firmwareRight = fw.message(2)?.string(2).orEmpty(),
                    firmwareLeft = fw.message(3)?.string(2).orEmpty(),
                )
            }
        }
        maestro.call(peer, "GetHardwareInfo")?.payload?.let(Proto::parse)?.let { hw ->
            val serials = hw.message(7)
            _metadata.update {
                it.copy(
                    deviceType = hw.int(1),
                    sku = hw.int(2),
                    serialCase = serials?.string(1).orEmpty(),
                    serialRight = serials?.string(2).orEmpty(),
                    serialLeft = serials?.string(3).orEmpty(),
                )
            }
        }
        Log.i(TAG, "metadata: ${_metadata.value}")
        // Changes from either bud (made with the buds' own gestures, Android's settings page or here).
        for (bud in BUDS) {
            maestro.subscribe(bud, "SubscribeToSettingsChanges") { payload ->
                Proto.parse(payload)?.message(4)?.fields?.forEach { field ->
                    val before = _state.value.settings[field.number]
                    if (before == null || Proto.describe(before) != Proto.describe(field)) {
                        Log.i(TAG, "setting ${field.number} changed: ${Proto.describe(field)} (was ${before?.let(Proto::describe)}) [$bud]")
                    }
                    setSetting(field)
                }
            }
            maestro.subscribe(bud, "SubscribeToOobeActions") { payload ->
                val action = Proto.parse(payload)?.int(1) ?: return@subscribe
                Log.i(TAG, "touch/oobe action $action from $bud")
                _touches.tryEmit(action)
            }
            maestro.subscribe(bud, "SubscribeToQuietModeStatus", MULTIPOINT) { payload ->
                Log.i(TAG, "multipoint quiet mode from $bud: ${Proto.parse(payload)?.let(Proto::describe)}")
            }
        }
        for (id in PixelBudsSetting.READ) readSetting(id)
        Log.i(TAG, "settings: ${_state.value.settings.mapValues { Proto.describe(it.value) }}")
        // Then the ids nobody has named, for mapping them.
        val found = PixelBudsSetting.EXPLORE.mapNotNull { id -> readSetting(id)?.let { id to Proto.describe(it) } }
        Log.i(TAG, "unnamed settings: $found")
    }

    private suspend fun readSetting(id: Int): Proto.Field? {
        val reply = maestro.call(peer, "ReadSetting", Proto.varintField(4, id)) ?: return null
        val field = Proto.parse(reply.payload)?.message(4)?.fields?.firstOrNull() ?: return null
        setSetting(field)
        return field
    }

    private fun setSetting(field: Proto.Field) {
        _state.update { it.copy(settings = it.settings + (field.number to field)) }
    }

    // ---- Writing settings ----------------------------------------------------------------------

    // The case only reports its level while a bud is in it, so the last one is kept (across
    // restarts too) and shown as last known while both buds are out.
    private val prefs = context.getSharedPreferences("pixel_buds", Context.MODE_PRIVATE)
    private val caseKey = "case_battery_${bluetoothDevice.address}"
    private val heldKey = "held_disconnected_${bluetoothDevice.address}"

    init {
        if (prefs.getBoolean(heldKey, false)) _settings.update { it.copy(stayDisconnected = true) }
        val saved = prefs.getString(caseKey, null)?.split(",")
        val level = saved?.getOrNull(0)?.toIntOrNull()
        val at = saved?.getOrNull(1)?.toLongOrNull() ?: 0L
        if (level != null) {
            _state.update {
                it.copy(battery = it.battery + Battery(BatteryComponent.CASE, level, BatteryStatus.DISCONNECTED, BatterySource.MAESTRO, at))
            }
        }
    }

    /** Latest unsent value per setting id; a slider drag only sends where it ended up. */
    private val outbox = LinkedHashMap<Int, ByteArray>()
    private val outboxSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unused in outboxSignal) {
                while (true) {
                    val (id, value) = synchronized(outbox) {
                        val first = outbox.entries.firstOrNull() ?: return@synchronized null
                        outbox.remove(first.key)
                        first.key to first.value
                    } ?: break
                    val ok = callEither("WriteSetting", Proto.write { bytes(4, value) }) != null
                    Log.i(TAG, "write setting $id = ${Proto.parse(value)?.let(Proto::describe)}: ${if (ok) "ok" else "failed"}")
                    // On failure, show what the buds really have.
                    if (!ok && synchronized(outbox) { id !in outbox }) readSetting(id)
                }
            }
        }
    }

    /** Sends a SettingValue whose field [id] [value] writes; shows it right away. */
    private fun write(id: Int, value: Proto.Writer.() -> Unit) {
        val bytes = Proto.write(value)
        Proto.parse(bytes)?.fields?.firstOrNull()?.let(::setSetting)
        synchronized(outbox) {
            outbox.remove(id)
            outbox[id] = bytes
        }
        outboxSignal.trySend(Unit)
    }

    fun setNoiseMode(mode: PixelBudsNoiseMode) = write(PixelBudsSetting.NOISE_MODE) { varint(PixelBudsSetting.NOISE_MODE, mode.value) }

    fun setFlag(id: Int, on: Boolean) = write(id) { bool(id, on) }

    fun setEq(bands: List<Float>) = write(PixelBudsSetting.EQ) {
        message(PixelBudsSetting.EQ) {
            bands.take(5).forEachIndexed { i, v -> float(i + 1, v.coerceIn(PixelBudsSetting.EQ_MIN, PixelBudsSetting.EQ_MAX)) }
        }
    }

    fun setBalance(value: Int) = write(PixelBudsSetting.BALANCE) { varint(PixelBudsSetting.BALANCE, PixelBudsSetting.balanceToRaw(value)) }

    fun setHoldActions(left: Int, right: Int) = write(PixelBudsSetting.HOLD_ACTIONS) {
        message(PixelBudsSetting.HOLD_ACTIONS) {
            message(1) { message(4) { varint(1, left) } }
            message(2) { message(4) { varint(1, right) } }
        }
    }

    fun setNoiseLoop(modes: Set<PixelBudsNoiseMode>) = write(PixelBudsSetting.NOISE_LOOP) {
        message(PixelBudsSetting.NOISE_LOOP) {
            bool(1, PixelBudsNoiseMode.NOISE_CANCELLATION in modes)
            bool(2, PixelBudsNoiseMode.OFF in modes)
            bool(3, PixelBudsNoiseMode.TRANSPARENCY in modes)
            bool(4, PixelBudsNoiseMode.ADAPTIVE in modes)
        }
    }

    fun setHeadGestures(on: Boolean) = write(PixelBudsSetting.HEAD_GESTURES) { varint(PixelBudsSetting.HEAD_GESTURES, if (on) 2 else 1) }

    /** For experiments: writes a plain number to any setting id. */
    fun setRaw(id: Int, value: Int) = write(id) { varint(id, value) }

    // ---- Ear tip fit test ----------------------------------------------------------------------

    /**
     * Starts the fit test on both buds: test mode on, then each bud listens while the phone plays
     * the test sound (the caller plays it, at about half volume, from [onReady]). Results arrive
     * per bud; [onDone] once both streams end or fail.
     */
    fun startFitTest(
        onReady: () -> Unit,
        onResult: (left: Boolean, PixelBudsFitResult) -> Unit,
        onDone: () -> Unit,
    ) {
        scope.launch {
            val testMode = callEither("WriteSetting", Proto.write { message(4) { varint(PixelBudsSetting.TEST_MODE, 1) } }) != null
            Log.i(TAG, "fit test: test mode ${if (testMode) "on" else "didn't turn on"}")
            delay(800)
            onReady()
            delay(800)
            // One count for each open stream, plus one held while starting, so a bud whose stream
            // ends early can't finish the test before the other bud has started.
            val open = AtomicInteger(1)
            // As Google's app: start the test on a bud, then subscribe to its results (subscribing
            // first fails with status 9, FAILED_PRECONDITION).
            for (bud in BUDS) {
                val started = maestro.call(bud, "StartTest", service = FIT_TEST) != null
                Log.i(TAG, "fit test on $bud: ${if (started) "started" else "didn't start"}")
                if (!started) continue
                open.incrementAndGet()
                val subscribed = maestro.subscribe(bud, "SubscribeToResults", FIT_TEST, onEnd = { status ->
                    Log.i(TAG, "fit test on $bud ended, status $status")
                    if (open.decrementAndGet() == 0) onDone()
                }) { payload ->
                    val m = Proto.parse(payload) ?: return@subscribe
                    val result = PixelBudsFitResult(fit = m.float(1) ?: 0f, noise = m.float(2) ?: 0f)
                    Log.i(TAG, "fit test on $bud: fit ${result.fit}, noise ${result.noise} -> ${result.grade} (raw ${Proto.describe(m)})")
                    onResult(bud == Peer.LEFT, result)
                }
                if (!subscribed) open.decrementAndGet()
            }
            if (open.decrementAndGet() == 0) onDone()
        }
    }

    fun endFitTest() {
        scope.launch {
            for (bud in BUDS) {
                maestro.call(bud, "EndTest", service = FIT_TEST)
                maestro.unsubscribe(bud, "SubscribeToResults", FIT_TEST)
            }
            callEither("WriteSetting", Proto.write { message(4) { varint(PixelBudsSetting.TEST_MODE, 0) } })
            Log.i(TAG, "fit test ended")
        }
    }

    // ---- Sound level (dosimeter) ---------------------------------------------------------------

    fun watchSoundLevel(on: Boolean) {
        if (!on) {
            BUDS.forEach { maestro.unsubscribe(it, "SubscribeToLiveDb", DOSIMETER) }
            _soundLevel.value = null
            return
        }
        scope.launch {
            maestro.subscribe(peer, "SubscribeToLiveDb", DOSIMETER, onEnd = { Log.i(TAG, "sound level stream ended: $it") }) { payload ->
                val intensity = Proto.parse(payload)?.float(2) ?: 0f
                _soundLevel.value = if (intensity > 0f) 10f * log10(intensity) else 0f
            }
            Log.i(TAG, "sound level: daily summaries ${callEither("FetchDailySummaries", service = DOSIMETER)?.payload?.let(Proto::parse)?.let(Proto::describe)}")
        }
    }

    // ---- Head gestures (experimental) ----------------------------------------------------------

    /** Starts nod/shake detection; [onGesture] gets each report. */
    fun startHeadGestures(onGesture: (PixelBudsHeadGesture) -> Unit) {
        scope.launch {
            for (bud in BUDS) {
                val started = maestro.call(bud, "StartDetection", service = HEAD_GESTURE) != null
                Log.i(TAG, "head gestures on $bud: ${if (started) "started" else "didn't start"}")
                if (!started) continue
                maestro.subscribe(bud, "SubscribeToResults", HEAD_GESTURE, onEnd = { Log.i(TAG, "head gestures on $bud ended: $it") }) { payload ->
                    val m = Proto.parse(payload)
                    Log.i(TAG, "head gesture from $bud: ${m?.let(Proto::describe)}")
                    val progress = m?.message(2)
                    onGesture(PixelBudsHeadGesture(gesture = m?.int(1), axis = progress?.int(1), swings = progress?.int(2)))
                }
            }
        }
    }

    fun stopHeadGestures() {
        scope.launch {
            for (bud in BUDS) {
                maestro.unsubscribe(bud, "SubscribeToResults", HEAD_GESTURE)
                maestro.call(bud, "EndDetection", service = HEAD_GESTURE)
            }
            Log.i(TAG, "head gestures stopped")
        }
    }

    private fun onRuntimeInfo(info: Proto.Message) {
        val now = System.currentTimeMillis()
        val batteries = info.message(6)
        fun battery(component: BatteryComponent, n: Int): Battery? {
            val b = batteries?.message(n) ?: return null
            val charging = b.int(2) == 2
            return Battery(
                component = component,
                level = b.int(1) ?: 0,
                status = if (charging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING,
                source = BatterySource.MAESTRO,
                timestamp = now,
            )
        }
        val set = listOfNotNull(
            battery(BatteryComponent.CASE, 1),
            battery(BatteryComponent.LEFT, 2),
            battery(BatteryComponent.RIGHT, 3),
        ).toSet()
        set.find { it.component == BatteryComponent.CASE }?.let { case ->
            prefs.edit().putString(caseKey, "${case.level},${case.timestamp}").apply()
        }
        // Placement: 1 right bud in case, 2 left bud in case (false values are left out).
        val placement = info.message(7)
        Log.i(TAG, "runtime: ${set.joinToString { "${it.component} ${it.level}% ${it.status}" }}, in case: right ${placement?.bool(1)}, left ${placement?.bool(2)}")
        _state.update { s ->
            // Keep the last known case level when the buds are out and it isn't reported.
            val kept = s.battery.filter { old -> set.none { it.component == old.component } && old.component == BatteryComponent.CASE }
                .map { it.copy(status = BatteryStatus.DISCONNECTED) }
            s.copy(
                battery = set + kept,
                rightInCase = placement?.bool(1) ?: false,
                leftInCase = placement?.bool(2) ?: false,
            )
        }
    }
}

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.maestro.MaestroClient
import me.kavishdevar.librepods.core.bluetooth.maestro.Proto

private const val TAG = "PixelBuds"

/** Noise control modes as the buds number them (Maestro setting 13). */
enum class PixelBudsNoiseMode(val value: Int) {
    OFF(1), NOISE_CANCELLATION(2), TRANSPARENCY(3), ADAPTIVE(4);

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.value == value }
    }
}

/** Maestro setting ids (pbpctrl's SettingValue field numbers, plus what captures showed). */
object PixelBudsSetting {
    const val IN_EAR_DETECTION = 2
    const val GESTURES = 4
    const val HOLD_ACTIONS = 7
    const val MULTIPOINT = 11
    const val NOISE_MODE = 13
    const val VOLUME_EQ = 15
    const val EQ = 16
    const val BALANCE = 17
    const val MONO = 19
    const val VOLUME_EXPOSURE_NOTIFICATIONS = 21
    const val CONVERSATION_DETECTION = 22

    val READ = listOf(NOISE_MODE, CONVERSATION_DETECTION, IN_EAR_DETECTION, MULTIPOINT, EQ, HOLD_ACTIONS,
        GESTURES, VOLUME_EQ, BALANCE, MONO, VOLUME_EXPOSURE_NOTIFICATIONS)
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
    /** Press-and-hold action per bud (left, right): 5 noise control, 6 assistant, 7 Gemini (likely). */
    val holdActions: Pair<Int?, Int?>? get() = settings[PixelBudsSetting.HOLD_ACTIONS]?.bytes?.let(Proto::parse)?.let { m ->
        fun bud(n: Int) = m.message(n)?.message(4)?.int(1)
        bud(1) to bud(2)
    }
    val bothInCase: Boolean get() = leftInCase == true && rightInCase == true
}

data class PixelBudsSettings(
    // See Device.heldDisconnected. Not saved across restarts yet.
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
}

/**
 * Google Pixel Buds, read over Maestro (see [MaestroClient]). Android owns the audio and the
 * Bluetooth connection; "connected" here means LibrePods holds the Maestro channel. That fails
 * while Google's Pixel Buds app is installed and holding it, and the device then stays AVAILABLE.
 */
@SuppressLint("MissingPermission")
class PixelBudsDevice(
    override val context: Context,
    override val bluetoothAdapter: BluetoothAdapter,
    override val bluetoothDevice: BluetoothDevice,
    currentState: ConnectionState,
) : Device<PixelBudsState, PixelBudsSettings, PixelBudsMetadata> {
    override val macAddress = MacAddress(bluetoothDevice.address)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maestro = MaestroClient(bluetoothDevice)
    private var peer = MaestroClient.Peer.RIGHT

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
        _settings.update { it.copy(stayDisconnected = true) }
        disconnectAudio()
        disconnect()
    }

    override fun connectByUser(): Boolean {
        Log.i(TAG, "connect asked for by the user")
        _settings.update { it.copy(stayDisconnected = false) }
        if (!isAclConnected()) runCatching { bluetoothDevice.connect() }
        return connect()
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
        _connectionState.value = if (isAclConnected()) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
    }

    private fun isAclConnected(): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(bluetoothDevice) as Boolean
    }.getOrDefault(false)

    private suspend fun readAll() {
        // Either bud can answer; use the first that does (one may be in the case or asleep).
        val software = maestro.call(MaestroClient.Peer.RIGHT, "GetSoftwareInfo")?.also { peer = MaestroClient.Peer.RIGHT }
            ?: maestro.call(MaestroClient.Peer.LEFT, "GetSoftwareInfo")?.also { peer = MaestroClient.Peer.LEFT }
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
        maestro.subscribe(peer, "SubscribeRuntimeInfo") { payload -> Proto.parse(payload)?.let(::onRuntimeInfo) }
        maestro.subscribe(peer, "SubscribeToSettingsChanges") { payload ->
            Proto.parse(payload)?.message(4)?.fields?.forEach { setSetting(it) }
        }
        for (id in PixelBudsSetting.READ) {
            val reply = maestro.call(peer, "ReadSetting", Proto.varintField(4, id)) ?: continue
            Proto.parse(reply.payload)?.message(4)?.fields?.forEach { setSetting(it) }
        }
        Log.i(TAG, "settings: ${_state.value.settings.keys.sorted()}, noise mode ${_state.value.noiseMode}")
    }

    private fun setSetting(field: Proto.Field) {
        _state.update { it.copy(settings = it.settings + (field.number to field)) }
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
        // Placement: 1 right bud in case, 2 left bud in case (false values are left out).
        val placement = info.message(7)
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

    @Suppress("unused")
    private val createdAt = SystemClock.elapsedRealtime()
}

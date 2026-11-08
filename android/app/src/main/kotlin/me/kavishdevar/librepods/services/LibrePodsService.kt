package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.EldDecoder
import me.kavishdevar.librepods.audio.WavWriter
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.ble.BLEManager
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.data.apple.AccessoryState
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.PixelBudsDevice
import me.kavishdevar.librepods.core.devices.PixelBudsNoiseMode
import me.kavishdevar.librepods.core.devices.PixelBudsState
import me.kavishdevar.librepods.core.bluetooth.maestro.MaestroClient
import me.kavishdevar.librepods.presentation.overlays.ProximityCardWindow
import me.kavishdevar.librepods.presentation.components.pixelbuds.PixelBudsArt
import me.kavishdevar.librepods.core.devices.PixelBudsMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.core.devices.format
import me.kavishdevar.librepods.core.devices.hasSameState
import me.kavishdevar.librepods.core.utils.redactMac
import me.kavishdevar.librepods.data.app.LibrePodsIntents
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.presentation.overlays.BannerBattery
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.calculateLevel
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.getOrNull
import kotlin.time.Duration
import kotlin.time.toJavaInstant

private const val TAG = "LibrePodsService"
private const val DEBUG_SHOW_ISLAND = "me.kavishdevar.librepods.DEBUG_SHOW_ISLAND"
private const val DEBUG_SHOW_CARD = "me.kavishdevar.librepods.DEBUG_SHOW_CARD"
private const val DEBUG_WATCH_CASE = "me.kavishdevar.librepods.DEBUG_WATCH_CASE"
private const val DEBUG_SENSORS = "me.kavishdevar.librepods.DEBUG_SENSORS"
// Sets or lifts the disconnect hold without connecting:
//   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_HOLD --ez on false -p me.kavishdevar.librepods.dev2
private const val DEBUG_HOLD = "me.kavishdevar.librepods.DEBUG_HOLD"
// Roadmap #7: logs every head-tracker sensor Android has (Pixel Buds Pro 2 register one while
// connected) and what one sends for a while:
//   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_HEAD_TRACKER --ei seconds 20 -p me.kavishdevar.librepods.dev2
private const val DEBUG_HEAD_TRACKER = "me.kavishdevar.librepods.DEBUG_HEAD_TRACKER"
// Pixel Buds: opens their Maestro channel and logs the replies to a few read-only calls:
//   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_MAESTRO --ei seconds 5 [--es uuid <uuid>] [--es host b] -p me.kavishdevar.librepods.dev2
private const val DEBUG_MAESTRO = "me.kavishdevar.librepods.DEBUG_MAESTRO"
// Pixel Buds read-only sources: Android's battery level and the buds' Fast Pair broadcasts:
//   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_PIXELBUDS --ei seconds 90 -p me.kavishdevar.librepods.dev2
private const val DEBUG_PIXELBUDS = "me.kavishdevar.librepods.DEBUG_PIXELBUDS"

@SuppressLint("MissingPermission")
class LibrePodsService: Service() {
    inner class LocalBinder : Binder() {
        fun getService(): LibrePodsService = this@LibrePodsService
    }

    private val binder = LocalBinder()

    private val _devices = MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap())
    val devices = _devices.asStateFlow()

    private val serviceCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val irkMap = mutableMapOf<MacAddress, ByteArray>()
    val encKeyMap = mutableMapOf<MacAddress, ByteArray>()

    private val eldDecoder = EldDecoder()

    private val wavWriters = mutableMapOf<Recording, WavWriter>()

    private val appleRepository by lazy {
        (application as LibrePodsApplication).appleRepository
    }

    private val appDataRepository by lazy {
        (application as LibrePodsApplication).appDataRepository
    }

    private val widgetConfigRepository by lazy {
        (application as LibrePodsApplication).widgetConfigRepository
    }

    private val heartRateRepository by lazy {
        (application as LibrePodsApplication).heartRateRepository
    }


    private val bleManager by lazy {
        BLEManager(
            context = this,
            scope = serviceCoroutineScope,

            scanMode = appDataRepository.settings.value.bleScanMode,
            reportDelay = appDataRepository.settings.value.bleReportDelay,

            getDevices = { devices.value },

            irkMap = irkMap,
            encKeyMap = encKeyMap
        )
    }

    @Suppress("unused")
    private val recordingRepository by lazy {
        (application as LibrePodsApplication).recordingRepository
    }

    private val healthConnectClient by lazy {
        (application as LibrePodsApplication).healthConnectClient
    }

    private val stemPressHandler by lazy { StemPressHandler(this) }

    private val headMouseController by lazy {
        HeadMouseController(serviceCoroutineScope) { device -> applyStemPressForwarding(device) }
    }

    // Starts and stops the head mouse when it is turned on or off (settings toggle, stem action).
    private fun observeHeadMouse() {
        HeadMouse.controller = headMouseController
        serviceCoroutineScope.launch {
            HeadMouse.requested.collect { on ->
                if (!on) {
                    headMouseController.stop()
                    return@collect
                }
                val device = _devices.value.values.filterIsInstance<AppleDevice>()
                    .firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }
                val accessibility = LibrePodsAccessibilityService.instance.value
                when {
                    device == null -> {
                        notifyHeadMouse(R.string.head_mouse_needs_airpods)
                        HeadMouse.requested.value = false
                    }
                    accessibility == null -> {
                        notifyHeadMouse(R.string.head_mouse_needs_accessibility)
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        HeadMouse.requested.value = false
                    }
                    else -> headMouseController.start(device, accessibility)
                }
            }
        }
    }

    private fun notifyHeadMouse(message: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show()
        }
    }

    // Debug builds only: show the island or the case card on demand, through the same code a real
    // connection or lid opening uses, for the first known AirPods.
    //   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_SHOW_ISLAND -p me.kavishdevar.librepods.dev2
    //   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_SHOW_CARD -p me.kavishdevar.librepods.dev2
    // Debug builds only (roadmap #6): log every case and buds proximity advert for a while, raw and
    // decrypted with the AirPods' encryption key, to see which bytes change when the case is
    // shaken, tilted, opened or its button pressed. Logs "CaseWatch" lines. The scan delivers each
    // advert as it arrives, unlike the batched scan the app normally runs.
    //   adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_WATCH_CASE --ei seconds 150 [--ez any true] -p me.kavishdevar.librepods.dev2
    @SuppressLint("MissingPermission")
    private fun debugWatchCase(seconds: Int, anyApple: Boolean = false) {
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun decrypt(block: ByteArray): String = encKeyMap.values.joinToString(",") { key ->
            try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                hex(cipher.doFinal(block))
            } catch (_: Exception) {
                "?"
            }
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
                if (data.size < 19 || data[0] != 0x07.toByte()) return
                val kind = when (data[2].toInt() and 0xFF) {
                    0x06 -> "case"
                    0x01 -> "buds"
                    else -> "other%02x".format(data[2])
                }
                // Case adverts carry the encrypted block right after the 0x06 subtype; buds adverts
                // carry it in their last 16 bytes.
                val block = if (kind == "case") data.copyOfRange(3, 19) else data.copyOfRange(data.size - 16, data.size)
                Log.i(
                    "CaseWatch",
                    "$kind ${result.device.address} rssi=${result.rssi} raw=${hex(data)} plain=${decrypt(block)}"
                )
            }
        }
        // With "any", match every Apple advert and pick ours out in the callback: some Bluetooth
        // chips may not apply the masked byte filter the app normally uses.
        val filters = if (anyApple) {
            listOf(ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(), byteArrayOf()).build())
        } else listOf(0x06, 0x01).map { subtype ->
            ScanFilter.Builder()
                .setManufacturerData(
                    0x004C,
                    byteArrayOf(0x07, 0x00, subtype.toByte()),
                    byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte())
                )
                .build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
            Log.i("CaseWatch", "watching for $seconds s, keys: ${encKeyMap.size}, any Apple advert: $anyApple")
        } catch (e: Exception) {
            Log.e("CaseWatch", "couldn't start the scan", e)
            return
        }
        serviceCoroutineScope.launch {
            delay(seconds.seconds)
            runCatching { scanner.stopScan(callback) }
            Log.i("CaseWatch", "watch done")
        }
    }

    // Debug builds only (roadmap #8): explore the AirPods' sensor services. Everything that comes
    // back is logged under the "SensorProbe" tag.
    //   list the sensors:  --es op descriptors
    //   start one:         --es op start --ei service 11 --ei interval_ms 50
    //   stop one:          --es op stop --ei service 11
    //   sweep:             --es op sweep --es services 1,2,11,12 --ei seconds 4 --ei interval_ms 50
    //   raw setting:       --es op raw --ei service 17 --ei setting 1 --es config 0140420f00
    //   sample logging:    --es op log --ez on true
    // e.g. adb shell am broadcast -a me.kavishdevar.librepods.DEBUG_SENSORS --es op descriptors -p me.kavishdevar.librepods.dev2
    private var sensorSweepJob: Job? = null

    private fun debugHeadTracker(seconds: Int) {
        val tag = "HeadTrackerProbe"
        val sensorManager = getSystemService(SensorManager::class.java)
        val trackers = (sensorManager.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER) +
            sensorManager.getSensorList(Sensor.TYPE_HEAD_TRACKER)).distinctBy { it.id to it.name }
        Log.i(tag, "head trackers: " + trackers.joinToString { "${it.name} (${it.vendor}, id ${it.id}, delay ${it.minDelay}..${it.maxDelay} us, dynamic ${it.isDynamicSensor})" })
        val tracker = trackers.firstOrNull() ?: return
        var count = 0
        var first = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (count == 0) first = event.timestamp
                count++
                // values: rotation vector (x, y, z, radians, axis-angle), angular velocity (x, y, z,
                // rad/s), discontinuity count
                Log.i(tag, "#$count t=${(event.timestamp - first) / 1_000_000}ms " + event.values.joinToString(" ") { "%.4f".format(it) })
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                Log.i(tag, "accuracy $accuracy")
            }
        }
        val registered = sensorManager.registerListener(listener, tracker, SensorManager.SENSOR_DELAY_GAME)
        Log.i(tag, "listening to ${tracker.name}: $registered")
        serviceCoroutineScope.launch {
            delay(seconds.seconds)
            sensorManager.unregisterListener(listener)
            Log.i(tag, "stopped after $count events in $seconds s")
        }
    }

    private fun debugSensors(intent: Intent) {
        val device = _devices.value.values.filterIsInstance<AppleDevice>()
            .firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }
        if (device == null) {
            Log.w("SensorProbe", "no connected AirPods")
            return
        }
        val aacp = device.aacp
        val interval = intent.getIntExtra("interval_ms", 50).milliseconds
        when (val op = intent.getStringExtra("op")) {
            "descriptors" -> Log.i("SensorProbe", "requesting descriptors, sent: ${aacp.requestSensorDescriptors()}")
            "start" -> {
                val service = intent.getIntExtra("service", -1)
                aacp.sensorProbeLogging = true
                Log.i("SensorProbe", "start service=$service every $interval, sent: ${aacp.setSensorServiceReportInterval(service, interval)}")
            }
            "stop" -> {
                val service = intent.getIntExtra("service", -1)
                Log.i("SensorProbe", "stop service=$service, sent: ${aacp.setSensorServiceReportInterval(service, Duration.ZERO)}")
            }
            "raw" -> {
                val service = intent.getIntExtra("service", -1)
                val setting = intent.getIntExtra("setting", 2)
                val config = intent.getStringExtra("config").orEmpty().chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                aacp.sensorProbeLogging = true
                Log.i("SensorProbe", "raw service=$service setting=$setting config=${config.toHexString()}, sent: ${aacp.sendRawSensorSetting(service, setting, config)}")
            }
            "gestures" -> {
                // Sets which stem gestures the AirPods forward (RAW_GESTURES_CONFIG) to any value,
                // and logs volume changes for two minutes: swipes on the stem change the volume.
                val bits = intent.getIntExtra("bits", 0)
                Log.i("SensorProbe", "gesture bits 0x${bits.toString(16)}, sent: ${device.setControlCommand(ControlCommandIdentifier.RAW_GESTURES_CONFIG, bits)}")
                val volumeLogger = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.i(
                            "SensorProbe",
                            "volume stream=${intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)} " +
                                "${intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)} -> " +
                                "${intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)}"
                        )
                    }
                }
                registerReceiver(volumeLogger, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), RECEIVER_EXPORTED)
                serviceCoroutineScope.launch {
                    delay(120.seconds)
                    runCatching { unregisterReceiver(volumeLogger) }
                }
            }
            "log" -> {
                aacp.sensorProbeLogging = intent.getBooleanExtra("on", true)
                Log.i("SensorProbe", "sample logging ${aacp.sensorProbeLogging}")
            }
            "sweep" -> {
                val services = intent.getStringExtra("services")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
                val seconds = intent.getIntExtra("seconds", 4)
                sensorSweepJob?.cancel()
                sensorSweepJob = serviceCoroutineScope.launch {
                    aacp.sensorProbeLogging = true
                    for (service in services) {
                        Log.i("SensorProbe", "sweep start service=$service every $interval, sent: ${aacp.setSensorServiceReportInterval(service, interval)}")
                        delay(seconds.seconds)
                        Log.i("SensorProbe", "sweep stop service=$service, sent: ${aacp.setSensorServiceReportInterval(service, Duration.ZERO)}")
                        delay(1.seconds)
                    }
                    aacp.sensorProbeLogging = false
                    Log.i("SensorProbe", "sweep done")
                }
            }
            else -> Log.w("SensorProbe", "unknown op $op")
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DEBUG_WATCH_CASE) {
                debugWatchCase(intent.getIntExtra("seconds", 60), intent.getBooleanExtra("any", false))
                return
            }
            if (intent.action == DEBUG_SENSORS) {
                debugSensors(intent)
                return
            }
            if (intent.action == DEBUG_HEAD_TRACKER) {
                debugHeadTracker(intent.getIntExtra("seconds", 20))
                return
            }
            if (intent.action == DEBUG_PIXELBUDS) {
                PixelBudsProbe.run(this@LibrePodsService, serviceCoroutineScope, intent.getIntExtra("seconds", 90),
                    unfiltered = intent.getBooleanExtra("all", false))
                return
            }
            if (intent.action == DEBUG_MAESTRO) {
                MaestroProbe.run(
                    this@LibrePodsService, serviceCoroutineScope, intent.getIntExtra("seconds", 5),
                    uuid = intent.getStringExtra("uuid")?.let(java.util.UUID::fromString)
                        ?: java.util.UUID.fromString("25e97ff7-24ce-4c4c-8951-f764a708f7b5"),
                    hostB = intent.getStringExtra("host") == "b"
                )
                return
            }
            if (intent.action == DEBUG_SHOW_CARD && intent.getBooleanExtra("pixel", false)) {
                _devices.value.values.filterIsInstance<PixelBudsDevice>().firstOrNull()?.let { showPixelBudsCard(it) }
                return
            }
            val device = _devices.value.values.filterIsInstance<AppleDevice>().firstOrNull() ?: return
            when (intent.action) {
                DEBUG_SHOW_ISLAND -> showIsland(device, IslandType.CONNECTED)
                DEBUG_SHOW_CARD -> proximityCardWindow.also { cardDevice = device.macAddress }.show(
                    name = displayName(device),
                    batteries = device.state.value.battery,
                    spec = AirPodsSpecs.getSpec(device.metadata.value.model),
                    onConnect = { device.connectByUser() },
                    airPodsConnected = device.connectionState.value == ConnectionState.CONNECTED
                )
                DEBUG_HOLD -> {
                    val on = intent.getBooleanExtra("on", false)
                    device.updateSettings { it.copy(stayDisconnected = on) }
                    Log.i(TAG, "debug: disconnect hold ${if (on) "set" else "lifted"} for ${device.macAddress.toRedactedString()}")
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            bleManager.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            updateLidWatcher()
        }
    }

    private var screenOn = true

    /** Shows the Pixel Buds case card when their lid opens (see [PixelBudsLidWatcher]). */
    private val pixelBudsLidWatcher by lazy {
        PixelBudsLidWatcher(
            context = this,
            onLidOpen = {
                _devices.value.values.filterIsInstance<PixelBudsDevice>()
                    .firstOrNull { it.connectionState.value != ConnectionState.CONNECTED }
                    ?.let { showPixelBudsCard(it) }
            },
            onLidClosed = {
                _devices.value.values.filterIsInstance<PixelBudsDevice>()
                    .firstOrNull { cardShows(it) && it.connectionState.value != ConnectionState.CONNECTED }
                    ?.let { proximityCardWindow.close() }
            },
        )
    }

    /** Watch for the Pixel Buds lid only while it matters: screen on, buds bonded, not connected. */
    private fun updateLidWatcher() {
        val buds = _devices.value.values.filterIsInstance<PixelBudsDevice>()
        val wanted = screenOn && buds.isNotEmpty() &&
            buds.none { it.connectionState.value == ConnectionState.CONNECTED || isAclConnected(it.bluetoothDevice) }
        pixelBudsLidWatcher.setActive(wanted)
    }

    private val healthConnectHeartRateWriter by lazy {
        HealthConnectHeartRateWriter(context = this, client = { healthConnectClient })
    }

    private val islandWindow: IslandWindow by lazy {
        (application as LibrePodsApplication).islandWindow
    }

    private val proximityCardWindow: ProximityCardWindow by lazy {
        (application as LibrePodsApplication).proximityCardWindow
    }

    @Suppress("unused")
    private fun observeCameraState() {
        CoroutineScope(Dispatchers.IO).launch {
            LibrePodsAccessibilityService.isCameraOpen.collect { isCamera ->
                if (isCamera) {
                    Log.d(TAG, "Camera is open")
                } else {
                    Log.d(TAG, "Camera is closed")
                }
            }
        }
    }

    private var isCallRinging = false

    // While a call rings or is on, the AirPods handle every stem press themselves, so answering,
    // declining and hanging up work as usual whatever the presses are mapped to.
    @Volatile
    private var isOnCall = false

    // Ear states last acted on per device, from the live connection only (reset on disconnect).
    private val lastEarState = mutableMapOf<MacAddress, Set<DeviceComponentState>>()

    // Hands the AirPods' keys to the scanner. They arrive over the control link the first time the
    // AirPods connect; the scanner used to get them only when the service started, so after a
    // fresh setup it couldn't read the AirPods' broadcasts (no lid, no case card) until a restart.
    private fun giveKeysToScanner(mac: MacAddress, magicKeys: Map<MagicKeyType, ByteArray>) {
        magicKeys[MagicKeyType.IRK]?.let {
            irkMap[mac] = it
            bleManager.updateIrkMap(irkMap)
        }
        magicKeys[MagicKeyType.ENC_KEY]?.let {
            encKeyMap[mac] = it
            bleManager.updateEncKeyMap(encKeyMap)
        }
    }

    /**
     * The name to show for these AirPods. Their own name arrives a few seconds after they connect,
     * and after a fresh setup it may not be saved yet, so the card and island used to show an empty
     * name; fall back to the name Android has for the paired device, then to "AirPods".
     */
    @SuppressLint("MissingPermission")
    private fun displayName(device: Device<*, *, *>): String {
        val own = device.metadata.value.name
        if (own.isNotBlank()) return own
        return runCatching { device.bluetoothDevice.alias ?: device.bluetoothDevice.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: if (device is PixelBudsDevice) "Pixel Buds" else "AirPods"
    }

    /** Whether both buds are known to be in the case: reported in it, or charging. */
    private fun budsInCase(state: AppleState): Boolean {
        fun inCase(bud: DeviceComponent, battery: BatteryComponent): Boolean =
            state.componentState.find { it.component == bud }?.status == ComponentStatus.IN_CASE ||
                state.battery.find { it.component == battery }?.status.let {
                    it == BatteryStatus.CHARGING || it == BatteryStatus.OPTIMIZED_CHARGING
                }
        return inCase(DeviceComponent.LEFT, BatteryComponent.LEFT) && inCase(DeviceComponent.RIGHT, BatteryComponent.RIGHT)
    }

    private fun applyStemPressForwarding(device: AppleDevice) {
        if (device.connectionState.value != ConnectionState.CONNECTED) return
        // While the head mouse runs, every press on either stem is one of its controls.
        val headMouse = HeadMouse.running.value && headMouseController.currentDevice == device
        val extra = if (headMouse) StemPressType.entries.toSet() else emptySet()
        val sent = device.applyStemPressForwarding(onCall = isOnCall, extra = extra)
        Log.i(
            TAG,
            "stem press forwarding for ${device.macAddress.toRedactedString()}: " +
                "${if (isOnCall) "none (on a call)" else device.settings.value.forwardedPressTypes()}, sent: $sent"
        )
    }

    private val telephonyCallback = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            val onCall = state != TelephonyManager.CALL_STATE_IDLE
            if (onCall != isOnCall) {
                isOnCall = onCall
                _devices.value.values.filterIsInstance<AppleDevice>().forEach(::applyStemPressForwarding)
            }
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                isCallRinging = true
                attemptConnect("phone")
                _devices.value.values.firstOrNull { device ->
                    device is AppleDevice &&
                    device.connectionState.value == ConnectionState.CONNECTED &&
                    device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                }?.let { device ->
                    (device as AppleDevice).detectHeadGestures { accept ->
                        if (!isCallRinging) return@detectHeadGestures

                        try {
                            val telecomManager = getSystemService(TelecomManager::class.java)

                            @Suppress("DEPRECATION")
                            if (accept) {
                                telecomManager?.acceptRingingCall(
                                    VideoProfile.STATE_AUDIO_ONLY
                                )
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_accepted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_rejected),
                                    Toast.LENGTH_SHORT
                                ).show()
                                telecomManager?.endCall()
                            }

                            isCallRinging = false
                            device.stopHeadTracking()

                        } catch (e: Exception) {
                            Log.e(TAG, "Error accepting call", e)
                        }
                    }
                }
            } else {
                if (isCallRinging) {
                    _devices.value.values.firstOrNull { device ->
                        device is AppleDevice &&
                        device.connectionState.value == ConnectionState.CONNECTED &&
                        device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                    }?.let { device ->
                        (device as AppleDevice).stopHeadTracking()
                    }
                }
            }
        }
    }

    private fun attemptConnect(trigger: String) {
        val candidates = bleManager.getCandidateDevicesForProxConnect()
        if (candidates.isEmpty()) {
            Log.d(TAG, "No valid BLE candidates found for $trigger takeover")
            return
        }

        for ((device, accessoryState) in candidates) {
            val settings = device.settings.value

            if (device.heldDisconnected) {
                Log.d(TAG, "Not connecting ${device.macAddress.toRedactedString()} for $trigger: the user disconnected it")
                continue
            }
            if (device is AppleDevice && budsInCase(device.state.value)) {
                Log.d(TAG, "Not connecting ${device.macAddress.toRedactedString()} for $trigger: both buds are in the case")
                continue
            }

            val shouldConnect = when (trigger) {
                "phone" -> when (accessoryState) {
                    AccessoryState.Disconnected -> settings.connectOnPhoneWhenAccessoryDisconnected
                    AccessoryState.Idle -> settings.connectOnPhoneWhenAccessoryIdle
                    AccessoryState.Music -> settings.connectOnPhoneWhenAccessoryMusic
                    else -> false
                }
                "media" -> when (accessoryState) {
                    AccessoryState.Disconnected -> settings.connectOnMediaWhenAccessoryDisconnected
                    AccessoryState.Idle -> settings.connectOnMediaWhenAccessoryIdle
                    AccessoryState.Music -> settings.connectOnMediaWhenAccessoryMusic
                    else -> false
                } else -> false
            }

            if (shouldConnect) {
                Log.i(
                    TAG,
                    "Connecting to ${device.macAddress.toRedactedString()} (state state: $accessoryState) for $trigger"
                )
                device.connect()
                break
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        observeAppSettings()

        registerBluetoothReceivers()

        loadDevices()

        bleManager.start()
        screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        updateLidWatcher()
        observeHeadMouse()
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) {
            // Exported so adb can reach it, but only senders holding DUMP may: adb's shell has it,
            // ordinary apps can't get it, so other apps on the phone can't drive these commands.
            registerReceiver(debugReceiver, IntentFilter().apply {
                addAction(DEBUG_SHOW_ISLAND)
                addAction(DEBUG_HOLD)
                addAction(DEBUG_HEAD_TRACKER)
                addAction(DEBUG_MAESTRO)
                addAction(DEBUG_PIXELBUDS)
                addAction(DEBUG_SHOW_CARD)
                addAction(DEBUG_WATCH_CASE)
                addAction(DEBUG_SENSORS)
            }, Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        }

        MediaController.initialize(
            audioManager = getSystemService(AudioManager::class.java),
            sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE),
            localMac = null, // TODO: smart routing. MAC_ADDRESS message gives host mac?,
            musicPlayed = {
                attemptConnect("media")
            }
        )

        if (
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val telephonyManager = getSystemService(TelephonyManager::class.java)

            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback
            )
        }

        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                LibrePodsIntents.ACTION_CONNECT -> {
                    val macAddress = intent.getStringExtra(LibrePodsIntents.Extras.MAC_ADDRESS)?.let { MacAddress(it) }
                    val device = devices.value[macAddress]
                    device?.connectByUser()
                }
                LibrePodsIntents.ACTION_SET_ANC_MODE -> {
                    val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                    val macAddress = intent.getStringExtra(LibrePodsIntents.Extras.MAC_ADDRESS)?.let { MacAddress(it) }

                    val ancMode = intent.getIntExtra(LibrePodsIntents.Extras.ANC_MODE, -1)
                    val device =
                        widgetConfigRepository.widgetConfigs.value.find { it.appWidgetId == appWidgetId }
                            ?.let { config ->
                                devices.value[config.macAddress]
                            } ?: devices.value[macAddress]
                        ?: devices.value.values.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

                    if (device != null && ancMode != -1) {
                        Log.i(
                            TAG,
                            "Setting ANC mode to $ancMode for device ${device.macAddress.toRedactedString()}"
                        )
                        when (device) {
                            is AppleDevice -> device.setControlCommand(
                                ControlCommandIdentifier.LISTENING_MODE,
                                ancMode
                            )
                            is PixelBudsDevice -> PixelBudsNoiseMode.from(ancMode)?.let(device::setNoiseMode)
                        }
                    } else {
                        Log.w(TAG, "No connected Apple device found or invalid ANC mode: $ancMode")
                    }
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        healthConnectHeartRateWriter.flush()
        runCatching { pixelBudsLidWatcher.setActive(false) }
        runCatching { unregisterReceiver(screenStateReceiver) }
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(debugReceiver) }
        unregisterBluetoothReceivers()
        bleManager.stop()
        serviceCoroutineScope.cancel("Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun onDeviceConnected(bluetoothDevice: BluetoothDevice) {
        val known = devices.value[MacAddress(bluetoothDevice.address)]
        if (known?.connectionState?.value == ConnectionState.CONNECTED || known?.connectionState?.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Device already connected or connecting: ${bluetoothDevice.address.redactMac()}")
            return
        }
        if (known?.heldDisconnected == true) {
            turnAway(known, "it connected on its own")
            return
        }

        val device = known ?: createDevice(bluetoothDevice, connectIfUp = false)

        if (device == null) {
            Log.d(TAG, "Unsupported device connected: ${bluetoothDevice.address}")
            return
        }

        when (device) {
            is AppleDevice -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Loading device ${device.macAddress.toRedactedString()} from db")

                appleRepository.load(device.macAddress)?.let { entity ->
                    val cache = entity.cache
                    Log.i(
                        TAG,
                        "Loaded cached state for device ${device.macAddress.toRedactedString()}: $cache"
                    )
                    val settings = entity.settings
                    Log.i(
                        TAG,
                        "Loaded settings for device ${device.macAddress.toRedactedString()}: $settings"
                    )
                    val metadata = entity.metadata
                    Log.i(
                        TAG,
                        "Loaded metadata for device ${device.macAddress.toRedactedString()}: $metadata"
                    )

                    giveKeysToScanner(device.macAddress, cache.magicKeys)

                    device.loadInitialState(
                        state = AppleState().copy(
                            capabilities = cache.capabilities,
                            magicKeys = cache.magicKeys,
                            controlStates = cache.controlStates,
                        ),
                        settings = settings,
                        metadata = metadata
                    )

                    if (device.settings.value.hrmAlertEnabled) {
                        device.startHr()
                    }
                }
            }
            is PixelBudsDevice -> {}
        }

        if (device is PixelBudsDevice) {
            // Pixel Buds Pro 2 connect when a bud leaves the case and disconnect when it's put
            // back, even with the lid open, so connecting isn't the lid opening: the first report
            // decides between the island and (rarely, both buds still in the case) the card.
            connectPixelBuds(device)
        } else device.connect()

        Log.i(
            TAG,
            "Device connected: ${device.macAddress.toRedactedString()} (${device.javaClass.simpleName})"
        )

        _devices.update { it + (device.macAddress to device) }
    }

    /**
     * Opens the Pixel Buds' Maestro channel. It only answers once Android has the buds connected
     * and settled, so wait a moment and try a few times. It fails for good while Google's Pixel
     * Buds app is installed and holding the channel.
     */
    private fun connectPixelBuds(device: PixelBudsDevice) {
        if (device.heldDisconnected) return
        serviceCoroutineScope.launch(Dispatchers.IO) {
            for (attempt in 1..4) {
                if (attempt > 1) delay(1000L)
                if (device.connectionState.value == ConnectionState.CONNECTED) return@launch
                if (!isAclConnected(device.bluetoothDevice)) return@launch
                if (device.connect()) return@launch
                Log.i(TAG, "Pixel Buds info channel not available yet (attempt $attempt)")
            }
        }
    }

    /**
     * Pixel Buds overlays: once a connection reports where the buds are, the case card
     * if both are in the case (the lid was just opened) and the island otherwise; taking the buds
     * out swaps the card for the island; disconnecting (lid closed) closes the card.
     */
    private fun observePixelBuds(device: PixelBudsDevice) {
        serviceCoroutineScope.launch {
            var previous = device.state.value
            var shownFor = -1
            device.state.collect { state ->
                if (!state.battery.hasSameState(previous.battery) || state.leftInCase != previous.leftInCase ||
                    state.rightInCase != previous.rightInCase || state.noiseMode != previous.noiseMode) {
                    updateDeviceNotification(device)
                }
                val connected = device.connectionState.value == ConnectionState.CONNECTED
                val connection = device.connectionNumber.value
                if (connected && state.leftInCase != null && state.battery.isNotEmpty() && shownFor != connection) {
                    shownFor = connection
                    if (state.bothInCase) {
                        if (cardShows(device)) proximityCardWindow.updateBatteries(state.battery)
                        else showPixelBudsCard(device)
                    } else {
                        if (cardShows(device)) proximityCardWindow.close()
                        showIsland(device, IslandType.CONNECTED)
                    }
                } else if (connected && previous.bothInCase && state.leftInCase != null && !state.bothInCase) {
                    if (cardShows(device)) proximityCardWindow.close()
                    showIsland(device, IslandType.CONNECTED)
                } else if (cardShows(device) && !state.battery.hasSameState(previous.battery)) {
                    proximityCardWindow.updateBatteries(state.battery)
                }
                if (islandWindow.isVisible && islandDevice == device.macAddress) {
                    if (!state.battery.hasSameState(previous.battery)) {
                        islandWindow.updateBatteries(bannerBatteries(state.battery))
                        state.battery.filter { (it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT) && it.status != BatteryStatus.DISCONNECTED }
                            .minOfOrNull { it.level }?.let(islandWindow::updateBattery)
                    }
                    state.noiseMode?.let { if (it != previous.noiseMode) islandWindow.updateNoiseControl(it.value, 50, true) }
                }
                previous = state
            }
        }
        serviceCoroutineScope.launch {
            device.connectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED && cardShows(device)) proximityCardWindow.close()
                updateDeviceNotification(device)
                updateLidWatcher()
            }
        }
    }

    // The device the case card is showing, so one device's updates don't land on another's card
    // (AirPods broadcasts kept updating the battery on the Pixel Buds card).
    private var cardDevice: MacAddress? = null

    private fun cardShows(device: Device<*, *, *>) = proximityCardWindow.isVisible && cardDevice == device.macAddress

    private fun showPixelBudsCard(device: PixelBudsDevice) {
        if (!Settings.canDrawOverlays(this)) return
        cardDevice = device.macAddress
        Log.i(TAG, "showing the case card for ${device.macAddress.toRedactedString()}")
        proximityCardWindow.show(
            name = displayName(device),
            batteries = device.state.value.battery,
            onConnect = { serviceCoroutineScope.launch(Dispatchers.IO) { device.connectByUser() } },
            airPodsConnected = true,
            images = if (device.metadata.value.deviceType == 6 || device.metadata.value.deviceType == null) {
                // Pro 2 (or not read yet: the only Pixel Buds here): a turntable of the Pro 2 model.
                val bud = PixelBudsArt.buds(device.metadata.value)
                ProximityCardWindow.CardImages(
                    primaryImageRes = bud,
                    caseImageRes = PixelBudsArt.case(device.metadata.value),
                    animation = { modifier ->
                        me.kavishdevar.librepods.presentation.components.pixelbuds.PixelBudsTurntable(PixelBudsArt.pro2Turntable, modifier)
                    },
                )
            } else {
                // Other Pixel Buds: Google's own animation, which draws their shape.
                ProximityCardWindow.CardImages(
                    primaryImageRes = PixelBudsArt.buds(device.metadata.value),
                    caseImageRes = PixelBudsArt.case(device.metadata.value),
                    lottieLightRes = PixelBudsArt.takeOutLight,
                    lottieDarkRes = PixelBudsArt.takeOutDark,
                )
            },
        )
    }

    /** Disconnects a device the user disconnected in LibrePods that came back some other way. */
    private fun turnAway(device: Device<*, *, *>, why: String) {
        Log.i(TAG, "turning away ${device.macAddress.toRedactedString()} ($why): the user disconnected it")
        device.disconnectAudio()
        device.disconnect()
        updateDeviceNotification(device = device)
    }

    /**
     * Android's own Disconnect (Bluetooth settings, the quick settings panel) takes only the audio
     * down. LibrePods' control link keeps the AirPods attached, and the audio comes straight back,
     * so the AirPods never disconnect. When the audio starts disconnecting and LibrePods didn't
     * ask for it, finish it as a user disconnect, but only if the connection itself stays up: the
     * AirPods dropping this phone (into the case, or off to another device) also reports the
     * audio as DISCONNECTING, after the connection has already gone.
     */
    private fun onAudioDisconnecting(mac: MacAddress, profile: String) {
        // Pixel Buds too: LibrePods' Maestro channel holds their connection open the same way.
        val device = devices.value[mac] ?: return
        if (device.heldDisconnected || device.connectionState.value != ConnectionState.CONNECTED) return
        if (SystemClock.elapsedRealtime() - device.audioDisconnectedByUsAt < 5_000) return
        serviceCoroutineScope.launch {
            delay(1.5.seconds)
            if (device.heldDisconnected) return@launch
            // Turning Bluetooth off disconnects everything; that isn't a disconnect of these AirPods.
            if (getSystemService(BluetoothManager::class.java)?.adapter?.state != BluetoothAdapter.STATE_ON) return@launch
            if (device.connectionState.value != ConnectionState.CONNECTED || !isAclConnected(device.bluetoothDevice)) {
                Log.i(TAG, "$profile went down with the connection to ${mac.toRedactedString()}: the device left, not a user disconnect")
                return@launch
            }
            Log.i(TAG, "Android disconnected $profile from ${mac.toRedactedString()} while the connection stayed up: treating it as the user disconnecting")
            device.disconnectByUser()
            updateDeviceNotification(device = device)
        }
    }

    /**
     * What these AirPods are doing with another device, from their broadcast, or null when they
     * aren't connected to one. Connected but idle counts: like an iPhone, LibrePods leaves the
     * AirPods with that device until this phone has audio to play (the media and call triggers
     * in attemptConnect handle that, per the connection settings). States we haven't identified
     * count as in use. Null too when this phone already has a connection to them, since then
     * the state may be this phone's own. The user's own Connect doesn't check this.
     */
    private fun busyElsewhere(device: AppleDevice): String? {
        val state = device.state.value
        if (state.accessoryStateByte < 0 || state.state == AccessoryState.Disconnected) return null
        if (isAclConnected(device.bluetoothDevice)) return null
        return if (state.state == AccessoryState.Unknown) "state 0x${"%02x".format(state.accessoryStateByte)}" else state.state.name
    }

    private fun isAclConnected(bluetoothDevice: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(bluetoothDevice) as Boolean
    }.getOrDefault(false)

    private fun profileStateName(state: Int) = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "$state"
    }

    private fun onDeviceDisconnected(mac: MacAddress) {
        Log.i(TAG, "Device disconnected: $mac")
        val device = devices.value[mac]?: return
        device.disconnect()
        updateDeviceNotification(device = device)
    }

    private fun loadDevices() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val bondedDevices = bluetoothAdapter.bondedDevices

        bondedDevices.forEach { bluetoothDevice ->
            val device = createDevice(bluetoothDevice)
            if (device != null) {
                val notificationManager = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    "device_${device.macAddress}",
                    "Device ${device.metadata.value.name}",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)

                _devices.update { it + (device.macAddress to device) }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice = intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                BluetoothDevice::class.java
            )
            val action = intent.action

            if (bluetoothDevice != null) {
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (bluetoothDevice.uuids == null) {
                            bluetoothDevice.fetchUuidsWithSdp()
                        } else {
                            onDeviceConnected(bluetoothDevice)
                        }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDeviceDisconnected(
                        MacAddress(
                            bluetoothDevice.address
                        )
                    )

                    BluetoothDevice.ACTION_UUID -> onDeviceConnected(bluetoothDevice)

                    BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED ->
                        Log.i(TAG, "disconnect requested for ${bluetoothDevice.address.redactMac()}")

                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val profile = if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) "A2DP" else "HFP"
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                        val mac = MacAddress(bluetoothDevice.address)
                        Log.i(TAG, "$profile ${profileStateName(previous)} -> ${profileStateName(state)} for ${mac.toRedactedString()}")
                        val device = devices.value[mac]
                        when {
                            state == BluetoothProfile.STATE_DISCONNECTING -> onAudioDisconnecting(mac, profile)
                            // Android or the AirPods brought the audio back over a connection
                            // that never dropped, so no ACL_CONNECTED came to turn it away.
                            device?.heldDisconnected == true &&
                                (state == BluetoothProfile.STATE_CONNECTING || state == BluetoothProfile.STATE_CONNECTED) ->
                                turnAway(device, "$profile came back")
                        }
                    }
                }
            }
        }
    }

    fun registerBluetoothReceivers() {
        val intentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.UUID")
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }

        registerReceiver(bluetoothReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    fun unregisterBluetoothReceivers() {
        unregisterReceiver(bluetoothReceiver)
    }

    /**
     * With [connectIfUp], connects the device once its saved settings are loaded if Android already
     * has it connected (or turns it away if the user disconnected it in LibrePods).
     */
    private fun createDevice(
        bluetoothDevice: BluetoothDevice,
        connectIfUp: Boolean = true
    ): Device<*, *, *>? {

        val aacpUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

        val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
        isConnectedMethod.isAccessible = true

        val device = when {
            bluetoothDevice.uuids?.contains(aacpUuid) == true -> {
                Log.i(TAG, "Apple device detected: ${bluetoothDevice.address.redactMac()}")
                AppleDevice(
                    context = this,
                    bluetoothAdapter = bluetoothAdapter,
                    bluetoothDevice = bluetoothDevice,
                    currentState = if (isConnectedMethod.invoke(bluetoothDevice) as Boolean) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
                )
            }

            bluetoothDevice.uuids?.any { it.uuid == MaestroClient.UUID } == true -> {
                Log.i(TAG, "Pixel Buds detected: ${bluetoothDevice.address.redactMac()}")
                PixelBudsDevice(
                    context = this,
                    bluetoothAdapter = bluetoothAdapter,
                    bluetoothDevice = bluetoothDevice,
                    currentState = if (isConnectedMethod.invoke(bluetoothDevice) as Boolean) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
                )
            }

            else -> null
        }

        when (device) {
            is AppleDevice -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Loading device ${device.macAddress.toRedactedString()} from db")

                appleRepository.load(device.macAddress)?.let { entity ->
                    val cache = entity.cache

                    // load irk at the earliest so we can start parsing
                    val irk = entity.cache.magicKeys[MagicKeyType.IRK]
                    if (irk != null) {
                        irkMap[device.macAddress] = irk
                        bleManager.updateIrkMap(irkMap)
                        Log.d(TAG, "Loaded IRK for device ${device.macAddress.toRedactedString()}")
                    }

                    val encKey = entity.cache.magicKeys[MagicKeyType.ENC_KEY]
                    if (encKey != null) {
                        encKeyMap[device.macAddress] = encKey
                        bleManager.updateEncKeyMap(encKeyMap)
                        Log.d(TAG, "Loaded ENC_KEY for device ${device.macAddress.toRedactedString()}")
                    }

                    Log.i(
                        TAG,
                        "Loaded cached state for device ${device.macAddress.toRedactedString()}: $cache"
                    )
                    val settings = entity.settings
                    Log.i(
                        TAG,
                        "Loaded settings for device ${device.macAddress.toRedactedString()}: $settings"
                    )
                    val metadata = entity.metadata
                    Log.i(
                        TAG,
                        "Loaded metadata for device ${device.macAddress.toRedactedString()}: $metadata"
                    )

                    device.loadInitialState(
                        state = AppleState().copy(
                            capabilities = cache.capabilities,
                            magicKeys = cache.magicKeys,
                            controlStates = cache.controlStates,
                        ),
                        settings = settings,
                        metadata = metadata
                    )
                }

                observeAppleState(device)
                observeAppleSettings(device)
                observeAppleMetadata(device)
                observeAppleMicrophoneFrames(device)
                observeAppleEvents(device)

                if (connectIfUp && isConnectedMethod.invoke(bluetoothDevice) as Boolean) {
                    if (device.heldDisconnected) turnAway(device, "it was connected when LibrePods started")
                    else {
                        Log.i(TAG, "${device.macAddress.toRedactedString()} was connected when LibrePods started, connecting")
                        device.connect()
                    }
                }
            }
            is PixelBudsDevice -> {
                observePixelBuds(device)
                if (connectIfUp && isConnectedMethod.invoke(bluetoothDevice) as Boolean) {
                    if (device.heldDisconnected) turnAway(device, "it was connected when LibrePods started")
                    else connectPixelBuds(device)
                }
            }
            null -> {}
        }

        if (device != null) observeConnectionState(device)

        return device
    }

    fun observeConnectionState(device: Device<*, *, *>) {
        serviceCoroutineScope.launch {
            var previousConnectionState = ConnectionState.DISCONNECTED
            device.connectionState.collect { state ->
                if (device is AppleDevice && cardShows(device)) proximityCardWindow.updateConnectionState(state == ConnectionState.CONNECTED)

                if (device is AppleDevice && state == ConnectionState.CONNECTED && previousConnectionState != ConnectionState.CONNECTED) {
                    serviceCoroutineScope.launch {
                        // Let the connection's setup packets go first.
                        delay(2.seconds)
                        applyStemPressForwarding(device)

                        // If the AirPods' ear report didn't differ from what their broadcasts said,
                        // no change was seen: treat connecting as starting from "no bud in".
                        val components = device.state.value.componentState
                        if (lastEarState[device.macAddress] == null && components.isNotEmpty()) {
                            lastEarState[device.macAddress] = components
                            processComponentStateChange(
                                device = device,
                                previousComponentState = emptySet(),
                                newComponentState = components,
                                disconnectWhenNotWearing = device.settings.value.disconnectWhenNotWearing
                            )
                        }

                        // Connected with no bud in an ear (lid opened with the buds inside): keep
                        // the audio off, or Android routes media to the AirPods in the case.
                        if (components.isNotEmpty() && earPresenceOf(components) == EarPresence.NONE &&
                            device.settings.value.disconnectWhenNotWearing
                        ) {
                            Log.i(TAG, "connected with no bud in an ear, keeping audio off")
                            device.disconnectAudio()
                        }
                    }
                }
                if (state != ConnectionState.CONNECTED) {
                    lastEarState.remove(device.macAddress)
                    if (headMouseController.currentDevice == device) HeadMouse.requested.value = false
                }

                if (previousConnectionState == ConnectionState.DISCONNECTED && state == ConnectionState.AVAILABLE) {
                    when (device) {
                        is AppleDevice -> {
                            val settings = device.settings.value
                            val state = device.state.value
                            if (device.heldDisconnected) {
                                Log.i(TAG, "device ${device.macAddress.toRedactedString()} is nearby, not connecting: the user disconnected it")
                            } else if (settings.alwaysConnectWhenAccessoryDisconnected && System.currentTimeMillis() - state.lastDisconnected > 5_000) {
                                if (budsInCase(state)) {
                                    // Like an iPhone: wait for a bud to come out (handled where the
                                    // ear state changes) rather than route audio to the case.
                                    Log.i(TAG, "device ${device.macAddress.toRedactedString()} is nearby with both buds in the case, not connecting yet")
                                } else {
                                    val busy = busyElsewhere(device)
                                    if (busy != null) {
                                        Log.i(TAG, "device ${device.macAddress.toRedactedString()} is nearby, not connecting: connected to another device ($busy)")
                                    } else {
                                        Log.i(TAG, "device ${device.macAddress.toRedactedString()} is nearby, connecting")
                                        device.connect()
                                    }
                                }
                            }
                        }
                        is PixelBudsDevice -> {}
                    }
                }

                previousConnectionState = state
            }
        }
    }

    fun observeAppSettings(): Job {
        var oldAppSettings: AppSettingsEntity = appDataRepository.settings.value

        return serviceCoroutineScope.launch {
            appDataRepository.settings.collect { settings ->
                if (oldAppSettings.bleReportDelay != settings.bleReportDelay) {
                    bleManager.updateReportDelay(settings.bleReportDelay)
                }
                if (oldAppSettings.bleScanMode != settings.bleScanMode) {
                    bleManager.updateScanMode(settings.bleScanMode)
                }

                islandWindow.updateSettings(settings)
                oldAppSettings = settings
            }
        }
    }

    fun observeAppleState(device: AppleDevice) {
        serviceCoroutineScope.launch {
            var previousState = device.state.value

            device.state.collect { state ->
                val deviceSettings = device.settings.value

                if (state.aacpPackets != previousState.aacpPackets) {
                    if (!appDataRepository.state.value.hasConnectedToAACP) {
                        appDataRepository.updateState { it.copy(hasConnectedToAACP = true) }
                    }
                    if (appDataRepository.state.value.firstSuccessfulConnectionTime == null) {
                        appDataRepository.updateState { it.copy(firstSuccessfulConnectionTime = System.currentTimeMillis()) }
                    }
                }

                if (state.magicKeys != previousState.magicKeys && state.magicKeys.isNotEmpty()) {
                    Log.i(TAG, "got keys for ${device.macAddress.toRedactedString()}, passing them to the scanner")
                    giveKeysToScanner(device.macAddress, state.magicKeys)
                }

                // Checked on its own, not as a branch of the when below: the lid usually opens in
                // the same advert that changes the accessory state, and a when runs only one branch.
                if (state.caseLidOpen != previousState.caseLidOpen) {
                    Log.i(TAG, "case lid ${if (state.caseLidOpen) "opened" else "closed"} for ${device.macAddress.toRedactedString()}")
                    if (state.caseLidOpen) {
                        if (appDataRepository.settings.value.proximityCardSettings.requireBothBudsInCase) {
                            val leftInCase = state.componentState.find { it.component == DeviceComponent.LEFT }?.status == ComponentStatus.IN_CASE ||
                                state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING ||
                                state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.OPTIMIZED_CHARGING

                            val rightInCase = state.componentState.find { it.component == DeviceComponent.RIGHT }?.status == ComponentStatus.IN_CASE ||
                                state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING ||
                                state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.OPTIMIZED_CHARGING

                            if (state.componentState.all { it.status == ComponentStatus.IN_CASE } || (leftInCase && rightInCase)) {
                                cardDevice = device.macAddress
                                proximityCardWindow.show(
                                    name = displayName(device),
                                    batteries = state.battery,
                                    spec = AirPodsSpecs.getSpec(device.metadata.value.model),
                                    onConnect = {
                                        device.connectByUser()
                                    },
                                    airPodsConnected = device.connectionState.value == ConnectionState.CONNECTED
                                )
                            } else if (cardShows(device)) proximityCardWindow.close()
                        } else {
                            cardDevice = device.macAddress
                            proximityCardWindow.show(
                                name = displayName(device),
                                batteries = state.battery,
                                spec = AirPodsSpecs.getSpec(device.metadata.value.model),
                                onConnect = {
                                    device.connectByUser()
                                },
                                airPodsConnected = device.connectionState.value == ConnectionState.CONNECTED
                            )
                        }
                    } else if (cardShows(device)) proximityCardWindow.close()
                }

                // On its own, not a branch of the when below, which runs only one branch per update
                // and could drop an ear change that came with another change.
                if (state.componentState != previousState.componentState) {
                    Log.i(
                        TAG,
                        "component state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "component state: ${state.componentState}")

                    val earDetectionCtrlCmdValue =
                        state.controlStates[ControlCommandIdentifier.EAR_DETECTION_CONFIG]
                            ?: byteArrayOf(0x01.toByte())
                    val earDetectionEnabled = earDetectionCtrlCmdValue[0] == 0x01.toByte() || deviceSettings.earDetectionEnabled // temporary, cache broken (?)

                    if (device.connectionState.value == ConnectionState.CONNECTED) {
                        // Act only on what the AirPods report over the live connection. States read
                        // from their broadcasts while disconnected can say "in ear" for buds still in
                        // the case, which connected audio and started playback in the case.
                        val previousEarState = lastEarState[device.macAddress] ?: emptySet()
                        lastEarState[device.macAddress] = state.componentState
                        Log.d(TAG, "ear detection enabled: $earDetectionEnabled")
                        if (earDetectionEnabled) {
                            processComponentStateChange(
                                device = device,
                                previousComponentState = previousEarState,
                                newComponentState = state.componentState,
                                disconnectWhenNotWearing = deviceSettings.disconnectWhenNotWearing
                            )
                        }
                    } else if (
                        deviceSettings.alwaysConnectWhenAccessoryDisconnected &&
                        !device.heldDisconnected &&
                        device.connectionState.value == ConnectionState.AVAILABLE &&
                        budsInCase(previousState) && !budsInCase(state)
                    ) {
                        // We hold off connecting while both buds are in the case; one just came out.
                        val busy = busyElsewhere(device)
                        if (busy != null) {
                            Log.i(TAG, "a bud left the case of ${device.macAddress.toRedactedString()}, not connecting: connected to another device ($busy)")
                        } else {
                            Log.i(TAG, "a bud left the case of ${device.macAddress.toRedactedString()}, connecting")
                            device.connect()
                        }
                    }

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)
                }

                when {
                    state.state != previousState.state -> {
                        if (state.state == AccessoryState.Disconnected) {
                            if (deviceSettings.alwaysConnectWhenAccessoryDisconnected && System.currentTimeMillis() - state.lastDisconnected > 5_000) {
                                Log.d(TAG, "device ${device.macAddress.toRedactedString()} is now disconnected, connecting")
                            }
                        }

                        if (MediaController.getMusicActive()) {
                            attemptConnect("media")
                        }
                    }

                    state.capabilities != previousState.capabilities -> {
                        Log.i(
                            TAG,
                            "capabilities changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "capabilities: ${state.capabilities}")

                        if (state.capabilities.isNotEmpty()) {
                            appleRepository.saveCacheFromState(device.macAddress, state)
                        }
                    }

                    !state.battery.hasSameState(previousState.battery) -> {
                        Log.i(
                            TAG,
                            "battery state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "battery state: ${state.battery.format()}. prev: ${previousState.battery.format()}")

                        Log.d(TAG, "updating widgets")
                        updateWidgets()

                        if (islandWindow.isVisible && islandDevice == device.macAddress) {
                            Log.d(TAG, "updating island window")
                            islandWindow.updateBatteries(bannerBatteries(state.battery))
                            val leftBattery = state.battery.find { it.component == BatteryComponent.LEFT }
                            val rightBattery = state.battery.find { it.component == BatteryComponent.RIGHT }

                            val leftLevel = leftBattery?.level ?: 0
                            val rightLevel = rightBattery?.level ?: 0

                            val displayBatteryLevel = when {
                                leftLevel > 0 && rightLevel > 0 -> minOf(leftLevel, rightLevel)
                                leftLevel > 0 -> leftLevel
                                rightLevel > 0 -> rightLevel
                                else -> 0
                            }

                            islandWindow.updateBattery(displayBatteryLevel)
                        }

                        if (cardShows(device)) proximityCardWindow.updateBatteries(state.battery)

                        updateDeviceNotification(device = device)

                        // TODO:Shizuku
                        /* Log.d(TAG, "updating bluetooth metadata")
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_CASE_BATTERY,
                            state.battery.find { it.component == BatteryComponent.CASE }?.level.toString()
                                .toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_CASE_CHARGING,
                            (if (state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                                || state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.OPTIMIZED_CHARGING
                            ) "1" else "0").toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_LEFT_BATTERY,
                            state.battery.find { it.component == BatteryComponent.LEFT }?.level.toString()
                                .toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_LEFT_CHARGING,
                            (if (state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                                || state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                            ) "1" else "0").toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_BATTERY,
                            state.battery.find { it.component == BatteryComponent.RIGHT }?.level.toString()
                                .toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_CHARGING,
                            (if (state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                                || state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                            ) "1" else "0").toByteArray()
                        ) */
                    }

                    state.controlStates != previousState.controlStates -> {
                        Log.i(
                            TAG,
                            "control states changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "control states: ${state.controlStates}")

                        if (state.controlStates.isNotEmpty()) {
                            appleRepository.saveCacheFromState(device.macAddress, state)
                        }

                        Log.d(TAG, "updating notification")
                        updateDeviceNotification(device = device)
                    }

                    state.conversationAwarenessState != previousState.conversationAwarenessState -> {
                        Log.i(
                            TAG,
                            "conversation awareness state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(
                            TAG,
                            "conversation awareness state: ${state.conversationAwarenessState}"
                        )

                        when (state.conversationAwarenessState) {
                            1 -> {
                                MediaController.startSpeaking()
                                MediaController.setVolume(
                                    deviceSettings.conversationAwarenessVolume.toInt()
                                )
                            }

                            2 -> {
                                MediaController.setVolume(
                                    deviceSettings.conversationAwarenessReducedVolume.toInt()
                                )
                            }

                            3 -> {
                                MediaController.setVolume(
                                    deviceSettings.conversationAwarenessVolume.toInt()
                                )
                            }

                            6, 7, 8, 9 -> {
                                MediaController.stopSpeaking()
                            }
                        }
                    }

                    state.currentHeartRate?.timestamp != previousState.currentHeartRate?.timestamp -> {
                        state.currentHeartRate?.let { heartRateSample ->
                            Log.i(
                                TAG,
                                "current heart rate changed from device ${device.macAddress.toRedactedString()}"
                            )
                            Log.d(
                                TAG,
                                "current heart rate: ${heartRateSample.bpm} bpm, timestamp: ${heartRateSample.timestamp.toJavaInstant()}. processing."
                            )

                            processHeartRateSample(
                                heartRateSample = heartRateSample,
                                interval = state.heartRateInterval,
                                alertThreshold = deviceSettings.hrmAlertThreshold
                            )
                        }
                    }

                    state.recordingState != previousState.recordingState -> {
                        Log.i(
                            TAG,
                            "recording state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "recording state: ${state.recordingState}")
                        if (previousState.recordingState.isRecording && !state.recordingState.isRecording) {
                            previousState.recordingState.recording?.let { recording ->
                                wavWriters[recording]?.close()
                            }
                        } else if (!previousState.recordingState.isRecording && state.recordingState.isRecording) {
                            state.recordingState.recording?.let { recording ->
                                try {
                                    val wavWriter = WavWriter(recording.file)
                                    wavWriters[recording] = wavWriter
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to create WavWriter for recording: ${recording.file}", e)
                                }
                            }
                        } else {
                            Log.w(TAG, "Recording state changed but no action taken. previous: ${previousState.recordingState}, current: ${state.recordingState}")
                        }
                    }
                }
                previousState = state
            }
        }
    }

    fun observeAppleSettings(device: AppleDevice) {
        serviceCoroutineScope.launch {
            var previousSettings = device.settings.value

            device.settings.collect { settings ->
                if (settings != previousSettings) {
                    Log.i(TAG, "settings changed for device ${device.macAddress.toRedactedString()}")
                    Log.d(TAG, "settings: $settings")

                    appleRepository.saveSettings(device.macAddress, settings)

                    if (settings.forwardedPressTypes() != previousSettings.forwardedPressTypes()) {
                        applyStemPressForwarding(device)
                    }
                }
                previousSettings = settings
            }
        }
    }

    fun observeAppleEvents(device: AppleDevice) {
        serviceCoroutineScope.launch {
            device.events.collect { event ->
                when (event) {
                    is AppleEvent.StemPress -> stemPressHandler.onStemPress(
                        device = device,
                        bud = event.bud,
                        type = event.pressType,
                        onCall = isOnCall
                    )
                    else -> {}
                }
            }
        }
    }

    fun observeAppleMetadata(device: AppleDevice) {
        serviceCoroutineScope.launch {
            var previousMetadata = device.metadata.value

            device.metadata.collect { metadata ->
                if (metadata != previousMetadata) {
                    Log.i(TAG, "metadata changed for device ${device.macAddress.toRedactedString()}")
                    Log.d(TAG, "metadata: $metadata")

                    appleRepository.saveMetadata(device.macAddress, metadata)

                    setAppleBluetoothMetadata(device)

                    val notificationManager = getSystemService(NotificationManager::class.java)
                    val channel =
                        notificationManager.getNotificationChannel("device_${device.macAddress}")
                    channel?.name = "Device ${metadata.name}"
                    notificationManager.createNotificationChannel(channel)
                }
                previousMetadata = metadata
            }
        }
    }

    fun observeAppleMicrophoneFrames(device: AppleDevice) {
        serviceCoroutineScope.launch {
            device.microphoneFrames.collect { frame ->
                val state = device.state.value

                if (state.recordingState.isRecording) {
                    eldDecoder.decode(
                        accessUnit = frame.accessUnit
                    ) { pcm ->
                        wavWriters[state.recordingState.recording]?.write(pcm)

                        device.updateMicrophoneState {
                            it.copy(
                                isActive = true,
                                packetsReceived = it.packetsReceived + 1,
                                durationMs = it.durationMs + 30,
                                level = calculateLevel(pcm)
                            )
                        }
                    }
                }
            }
        }
    }

    // The device the island (or banner) is showing, so another device's updates don't land on it.
    private var islandDevice: MacAddress? = null

    /** Battery parts for the banner's line, in the order Android's own banner uses. */
    private fun bannerBatteries(battery: Set<Battery>): List<BannerBattery> =
        listOf(BatteryComponent.LEFT to "Left", BatteryComponent.CASE to "Case", BatteryComponent.RIGHT to "Right")
            .mapNotNull { (component, label) ->
                battery.find { it.component == component && it.status != BatteryStatus.DISCONNECTED }
                    ?.let { BannerBattery(label, it.level, it.status == BatteryStatus.CHARGING) }
            }

    fun showIsland(
        device: Device<*, *, *>,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        Log.d(TAG, "Showing island window")

        val state = device.state.value
        val settings = device.settings.value

        when (state) {
            is AppleState -> {
                val device = device as AppleDevice
                val state = device.state.value
                val settings = settings as AppleSettings
                val metadata = device.metadata.value

                if (state.componentState.isEmpty()) {
                    Log.w(TAG, "No component state available, can't show island")
                    return
                }

                if (settings.showIslandPopup) {
                    if (!Settings.canDrawOverlays(this)) {
                        Log.w(TAG, "No permission for SYSTEM_ALERT_WINDOW")
                        return
                    }

                    Log.i(TAG, "Showing island for device ${device.macAddress.toRedactedString()}")

                    val leftBattery = state.battery.find { it.component == BatteryComponent.LEFT }?.level ?: 0
                    val rightBattery = state.battery.find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    val batteryPercentage = leftBattery.coerceAtMost(rightBattery)

                    islandWindow.show(
                        name = displayName(device),
                        batteryLevel = batteryPercentage,
                        type = type,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,

                        openApp = {
                            val intent = Intent(
                                this,
                                MainActivity::class.java,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },

                        showOffListeningMode = state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1,
                        noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                        onNoiseControlModeChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.LISTENING_MODE, it
                            )
                        },
                        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toInt() ?: 50,
                        onAdaptiveStrengthChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.AUTO_ANC_STRENGTH, it
                            )
                        },
                        batteries = bannerBatteries(state.battery),
                    )
                    islandDevice = device.macAddress
                }
            }

            is PixelBudsState -> {
                if (!Settings.canDrawOverlays(this)) return
                val levels = state.battery
                    .filter { (it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT) && it.status != BatteryStatus.DISCONNECTED }
                    .map { it.level }
                if (levels.isEmpty()) return
                Log.i(TAG, "Showing island for Pixel Buds ${device.macAddress.toRedactedString()}")
                islandWindow.show(
                    name = displayName(device),
                    batteryLevel = levels.min(),
                    type = type,
                    reversed = reversed,
                    otherDeviceName = otherDeviceName,
                    openApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    showOffListeningMode = true,
                    noiseControlModeValue = state.noiseMode?.value ?: PixelBudsNoiseMode.NOISE_CANCELLATION.value,
                    onNoiseControlModeChanged = { value ->
                        PixelBudsNoiseMode.from(value)?.let { (device as PixelBudsDevice).setNoiseMode(it) }
                    },
                    adaptiveStrength = 50,
                    onAdaptiveStrengthChanged = { },
                    imageRes = PixelBudsArt.buds(device.metadata.value as PixelBudsMetadata),
                    monochromeIconRes = PixelBudsArt.icon,
                    batteries = bannerBatteries(state.battery),
                )
                islandDevice = device.macAddress
            }

            else -> {
                Log.d(
                    TAG,
                    "Unsupported device state: ${device.state.value.javaClass.simpleName}, not showing island"
                )
                return
            }
        }
    }

    fun updateDeviceNotification(
        device: Device<*, *, *>
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = device.macAddress.toNotificationId()

        if (device.connectionState.value == ConnectionState.CONNECTED || device.connectionState.value == ConnectionState.AVAILABLE) {
            when (device) {
                is AppleDevice -> {
                    Log.d(
                        TAG,
                        "Updating notification for Apple device ${device.macAddress.toRedactedString()}"
                    )

                    val updatedNotificationBuilder =
                        NotificationCompat.Builder(this, "device_${device.macAddress}")
                            .setSmallIcon(R.drawable.ic_airpods)
                            .setContentTitle(displayName(device))
                            .setContentText(device.state.value.battery.joinToString(" ") { "${it.component.name[0]}: ${it.level}%" })
                            .setContentIntent(pendingIntent)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(true)
                    if (device.connectionState.value == ConnectionState.CONNECTED) {
                        if (device.state.value.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0) == 0x01.toByte()) {
                            updatedNotificationBuilder.addAction(
                                R.drawable.ic_noise_cancellation,
                                getString(R.string.off),
                                PendingIntent.getService(
                                    this,
                                    notificationId + 1,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                        putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                        putExtra(LibrePodsIntents.Extras.ANC_MODE, 1)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                        }
                        updatedNotificationBuilder
                            .addAction(
                                R.drawable.ic_transparency, // icon never shows up?? (tested on AOSP ROMs)
                                getString(R.string.transparency),
                                PendingIntent.getService(
                                    this,
                                    notificationId + 3,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                        putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                        putExtra(LibrePodsIntents.Extras.ANC_MODE, 3)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                            .addAction(
                                R.drawable.ic_adaptive,
                                getString(R.string.adaptive),
                                PendingIntent.getService(
                                    this,
                                    notificationId + 4,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                        putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                        putExtra(LibrePodsIntents.Extras.ANC_MODE, 4)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                            .addAction(
                                R.drawable.ic_noise_cancellation,
                                getString(R.string.noise_cancellation),
                                PendingIntent.getService(
                                    this,
                                    notificationId + 2,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                        putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                        putExtra(LibrePodsIntents.Extras.ANC_MODE, 2)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                    } else {
                        updatedNotificationBuilder.addAction(
                            R.drawable.ic_power,
                            getString(R.string.connect),
                            PendingIntent.getService(
                                this,
                                notificationId + 5,
                                Intent(this, LibrePodsService::class.java).apply {
                                    action = LibrePodsIntents.ACTION_CONNECT
                                    putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }

                    val updatedNotification = updatedNotificationBuilder.build()

                    notificationManager.notify(notificationId, updatedNotification)
                }
                is PixelBudsDevice -> {
                    val state = device.state.value
                    val parts = listOf(BatteryComponent.LEFT to "L", BatteryComponent.RIGHT to "R", BatteryComponent.CASE to "Case")
                        .mapNotNull { (component, label) ->
                            state.battery.find { it.component == component && it.status != BatteryStatus.DISCONNECTED }?.let {
                                "$label ${it.level}%" + if (it.status == BatteryStatus.CHARGING) " (charging)" else ""
                            }
                        }
                    val text = when {
                        parts.isNotEmpty() -> parts.joinToString("   ")
                        device.connectionState.value == ConnectionState.CONNECTED -> "Connected"
                        else -> "Connected; details unavailable"
                    }
                    val builder = NotificationCompat.Builder(this, "device_${device.macAddress}")
                        .setSmallIcon(PixelBudsArt.icon)
                        .setContentTitle(displayName(device))
                        .setContentText(text)
                        .setContentIntent(pendingIntent)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                    if (device.connectionState.value == ConnectionState.AVAILABLE) {
                        builder.addAction(
                            R.drawable.ic_power,
                            getString(R.string.connect),
                            PendingIntent.getService(
                                this,
                                notificationId + 5,
                                Intent(this, LibrePodsService::class.java).apply {
                                    action = LibrePodsIntents.ACTION_CONNECT
                                    putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }
                    if (device.connectionState.value == ConnectionState.CONNECTED) {
                        // Same numbering as AirPods: 1 off, 2 noise cancellation, 3 transparency, 4 adaptive.
                        val current = state.noiseMode?.value
                        for ((mode, label, icon) in listOf(
                            Triple(1, R.string.off, R.drawable.ic_noise_cancellation),
                            Triple(3, R.string.transparency, R.drawable.ic_transparency),
                            Triple(4, R.string.adaptive, R.drawable.ic_adaptive),
                            Triple(2, R.string.noise_cancellation, R.drawable.ic_noise_cancellation),
                        )) {
                            builder.addAction(
                                icon,
                                (if (mode == current) "• " else "") + getString(label),
                                PendingIntent.getService(
                                    this,
                                    notificationId + mode,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                        putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                        putExtra(LibrePodsIntents.Extras.ANC_MODE, mode)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                        }
                    }
                    notificationManager.notify(notificationId, builder.build())
                }
            }

        } else {
            notificationManager.cancel(notificationId)
        }
    }

    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "foreground_service_status",
            getString(R.string.foreground_service_status),
            NotificationManager.IMPORTANCE_NONE
        )

        val hrmAlertChannel = NotificationChannel(
            "hrm_alert",
            getString(R.string.heart_rate_alert),
            NotificationManager.IMPORTANCE_HIGH
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(hrmAlertChannel)

        val notificationSettingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, "foreground_service_status")
        }

        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "foreground_service_status")
            .setSmallIcon(R.drawable.ic_airpods).setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.foreground_notification_description))
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateWidgets() {
        // TODO: Room
        val widgetMobileBatteryEnabled = getSharedPreferences(
            "settings",
            MODE_PRIVATE
        ).getBoolean("show_phone_battery_in_widget", false)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            // TODO: device-specific widgets
            val device =
                devices.value.values.firstOrNull { it is AppleDevice && it.state.value.battery.any { it.level > 0 } } as? AppleDevice

            Log.d(TAG, "picked device: ${device?.macAddress?.toRedactedString()} for widget update")

            val leftBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    private fun processComponentStateChange(
        device: Device<*, *, *>,
        previousComponentState: Set<DeviceComponentState>,
        newComponentState: Set<DeviceComponentState>,
        disconnectWhenNotWearing: Boolean
    ) {
        val old = earPresenceOf(previousComponentState)
        val new = earPresenceOf(newComponentState)
        if (old == new) return

        Log.d(TAG, "earPresence: $old -> $new")

        // new != NONE because old!=new
        if (old == EarPresence.NONE) {
            showIsland(
                device = device,
                type = IslandType.CONNECTED
            )
            Log.i(TAG, "User put in at least one component, showing island.")
        }

        if (new == EarPresence.NONE && appDataRepository.settings.value.islandSettings.keepCompactVisible) {
            islandWindow.close()
        }

        var justEnabledA2dp = false

        when {
            old == EarPresence.NONE -> {
                Log.d(
                    TAG,
                    "User put in at least one component, enabling audio for device ${device.macAddress.toRedactedString()}"
                )
                device.enableAudio()
                device.connectA2dp()
                justEnabledA2dp = true

                device.waitForA2dpConnection(this) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }

                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            new == EarPresence.NONE -> {
                MediaController.sendPause(force = true)
                if (disconnectWhenNotWearing) {
                    Log.d(
                        TAG,
                        "Disconnecting audio for device ${device.macAddress.toRedactedString()} because user took out all components and disconnectWhenNotWearing is true"
                    )
                    device.disableAudio()
                    device.disconnectAudio()
                }
            }
        }

        when {
            new == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User put in all components.")
                MediaController.userPlayedTheMedia = false
                if (!justEnabledA2dp) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            old == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User took one out.")
                MediaController.userPlayedTheMedia = false
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun processHeartRateSample(heartRateSample: HeartRateSample, interval: Duration, alertThreshold: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "inserting to local db")
            heartRateRepository.insert(heartRateSample)
        }

        CoroutineScope(Dispatchers.Default).launch {
            val notificationManager = getSystemService(NotificationManager::class.java)

            if (heartRateSample.bpm > alertThreshold) {
                val notification = NotificationCompat.Builder(this@LibrePodsService, "hrm_alert")
                    .setSmallIcon(R.drawable.ic_pulse_alert)
                    .setContentTitle(getString(R.string.high_heart_rate))
                    .setContentText(
                        getString(
                            R.string.high_heart_rate_notification_text,
                            heartRateSample.bpm
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .build()

                notificationManager.notify(999, notification)
            } else {
                if (notificationManager.activeNotifications.any { it.id == 999 }) {
                    notificationManager.cancel(999)
                }
            }
        }

        healthConnectHeartRateWriter.add(heartRateSample, interval)
    }

    // TODO: Shizuku
    private fun setAppleBluetoothMetadata(
        @Suppress("unused") device: AppleDevice
    ) {
        /* val macAddress = device.macAddress.value
        val metadata = device.metadata.value
        val spec = AirPodsSpecs.getSpec(metadata.model)

        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MAIN_ICON,
            resToUri(spec.primaryImageRes).toString().toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_MODEL_NAME, metadata.modelNumber.toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_DEVICE_TYPE,
            BluetoothMetadata.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
        )
        spec.caseImageRes?.let {
            shizuku.setMetadata(
                macAddress,
                BluetoothMetadata.METADATA_UNTETHERED_CASE_ICON,
                resToUri(it).toString().toByteArray()
            )
        }
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.RIGHT }.imageRes).toString().toByteArray()
//        )
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_LEFT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.LEFT }.imageRes).toString().toByteArray()
//        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MANUFACTURER_NAME,
            metadata.manufacturer.toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_COMPANION_APP, packageName.toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        Log.d(TAG, "Metadata set for apple device ${device.macAddress.toRedactedString()}")
     */
    }
}

// TODO: move out of this file
private enum class EarPresence { NONE, PARTIAL, FULL }

private fun earPresenceOf(components: Set<DeviceComponentState>): EarPresence {
    if (components.isEmpty()) return EarPresence.NONE
    val inEarCount = components.count { it.status == ComponentStatus.IN_EAR }
    return when (inEarCount) {
        0 -> EarPresence.NONE
        components.size -> EarPresence.FULL
        else -> EarPresence.PARTIAL
    }
}

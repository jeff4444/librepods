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
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Debug probe for read-only Pixel Buds info that doesn't need the Maestro channel (which Google's
 * Pixel Buds app holds): Android's own battery level for the connected buds, and every Fast Pair
 * broadcast (service data 0xFE2C) and Google manufacturer data (company 0x00E0) seen nearby.
 * Logs under "PixelBudsProbe".
 */
object PixelBudsProbe {
    private const val TAG = "PixelBudsProbe"
    private val MAESTRO_UUID: UUID = UUID.fromString("25e97ff7-24ce-4c4c-8951-f764a708f7b5")
    private val FAST_PAIR = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
    private const val GOOGLE = 0x00E0
    private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @SuppressLint("MissingPermission")
    /** [unfiltered] scans everything (needs the screen on) instead of using the scan filters. */
    fun run(context: Context, scope: CoroutineScope, seconds: Int, unfiltered: Boolean = false) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val buds = adapter.bondedDevices.firstOrNull { d -> d.uuids?.any { it.uuid == MAESTRO_UUID } == true }
        if (buds != null) {
            val level = runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(buds) as Int }
            Log.i(TAG, "${buds.name}: class ${buds.bluetoothClass}, type ${buds.type}, battery via Android ${level.getOrElse { "failed: ${it.cause?.message ?: it.message}" }}")
            Log.i(TAG, "uuids: ${buds.uuids?.joinToString { it.uuid.toString() }}")
        } else {
            Log.w(TAG, "no bonded Pixel Buds")
        }

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                Log.i(TAG, "battery broadcast: ${device?.name} ${intent.getIntExtra(EXTRA_BATTERY_LEVEL, -100)}")
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(ACTION_BATTERY_LEVEL_CHANGED), Context.RECEIVER_EXPORTED)

        val scanner = adapter.bluetoothLeScanner
        val seen = HashMap<String, String>()
        val lastLogged = HashMap<String, Long>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = log(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::log)
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }

            private fun log(result: ScanResult) {
                val record = result.scanRecord ?: return
                val fastPair = record.getServiceData(FAST_PAIR)
                val google = record.getManufacturerSpecificData(GOOGLE)
                if (fastPair == null && google == null) return
                val line = buildString {
                    if (fastPair != null) append(" fastpair=").append(fastPair.hex())
                    if (google != null) append(" google=").append(google.hex())
                }
                // Per address: log changes, and otherwise at most once a second, so the log shows
                // when a broadcast starts and stops without flooding.
                val now = android.os.SystemClock.elapsedRealtime()
                val last = lastLogged[result.device.address] ?: 0L
                if (seen.put(result.device.address, line) != line || now - last >= 1000) {
                    lastLogged[result.device.address] = now
                    Log.i(TAG, "advert ${result.device.address} rssi=${result.rssi}$line raw=${record.bytes.hex().trimEnd('0')}")
                }
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceData(FAST_PAIR, ByteArray(0), ByteArray(0)).build(),
            ScanFilter.Builder().setManufacturerData(GOOGLE, ByteArray(0)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (unfiltered) scanner?.startScan(null, settings, callback) else scanner?.startScan(filters, settings, callback)
        Log.i(TAG, "scanning for $seconds s")
        scope.launch {
            delay(seconds.seconds)
            runCatching { scanner?.stopScan(callback) }
            runCatching { context.unregisterReceiver(batteryReceiver) }
            Log.i(TAG, "done; ${seen.size} addresses")
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

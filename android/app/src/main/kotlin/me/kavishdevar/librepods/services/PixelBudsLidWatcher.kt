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
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log

/**
 * Tells when a Pixel Buds case opens, from the buds' broadcasts. Pixel Buds Pro 2 don't connect
 * when the lid opens (only when a bud comes out), but they do broadcast: a Fast Pair beacon
 * (service data 0xFE2C, extended advertising) every few seconds with the lid closed, and several a
 * second while it's open, alongside a Find My Device frame (service data 0xFEAA).
 *
 * The broadcasts change address and are keyed to Google's account keys, so they can't be tied to
 * these buds for certain; a burst (3 within 1.5 s) that's close (-55 dBm or stronger) and carries
 * the Find My Device frame counts as this pair's lid opening, and the burst ending as it closing.
 * Scans only while asked to (screen on, buds bonded and not connected), at low latency.
 *
 * The case keeps broadcasting fast for a while (about 20 s, measured) after the buds go back in,
 * lid open or closed, which looked like the lid opening. So when watching starts right after the
 * buds went in, a burst only counts once the broadcasts have slowed for [CLOSED_AFTER_MS] first.
 */
class PixelBudsLidWatcher(
    private val context: Context,
    private val onLidOpen: () -> Unit,
    private val onLidClosed: () -> Unit,
) {
    private companion object {
        const val TAG = "PixelBudsLid"
        val FAST_PAIR: ParcelUuid = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
        val FIND_MY_DEVICE: ParcelUuid = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")
        const val MIN_RSSI = -55
        const val BURST = 3
        const val BURST_WINDOW_MS = 1500L
        const val CLOSED_AFTER_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recent = HashMap<String, ArrayDeque<Long>>()
    private var scanning = false
    private var open = false
    private var lastBurstAt = 0L
    // False while waiting for the case to quiet down after the buds went back in.
    private var armed = true

    private val armCheck = object : Runnable {
        override fun run() {
            if (armed || !scanning) return
            if (SystemClock.elapsedRealtime() - lastBurstAt >= CLOSED_AFTER_MS) {
                armed = true
                Log.i(TAG, "the case has quieted down; watching for the lid opening")
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }

    private val closeCheck = object : Runnable {
        override fun run() {
            if (!open) return
            if (SystemClock.elapsedRealtime() - lastBurstAt >= CLOSED_AFTER_MS) {
                open = false
                Log.i(TAG, "lid closed (broadcasts slowed down)")
                onLidClosed()
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
        }
    }

    /** [waitForQuiet]: the buds just went into the case; ignore its broadcasts until they slow down. */
    @SuppressLint("MissingPermission")
    fun setActive(active: Boolean, waitForQuiet: Boolean = false) {
        if (active && waitForQuiet && armed) disarm()
        if (active == scanning) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        if (active) {
            val filters = listOf(ScanFilter.Builder().setServiceData(FAST_PAIR, ByteArray(0), ByteArray(0)).build())
            // Extended advertising: only reported with legacy-only scanning turned off.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                .build()
            runCatching { scanner.startScan(filters, settings, callback) }
                .onSuccess { scanning = true; Log.i(TAG, "watching for the case lid") }
                .onFailure { Log.w(TAG, "couldn't start the scan: ${it.message}") }
        } else {
            runCatching { scanner.stopScan(callback) }
            scanning = false
            recent.clear()
            handler.removeCallbacks(armCheck)
            armed = true
            if (open) {
                open = false
                handler.removeCallbacks(closeCheck)
                onLidClosed()
            }
            Log.i(TAG, "stopped watching for the case lid")
        }
    }

    private fun disarm() {
        armed = false
        lastBurstAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(armCheck)
        handler.postDelayed(armCheck, 500)
        Log.i(TAG, "the buds just went into the case; waiting for its broadcasts to slow down")
    }

    private fun onResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        if (record.getServiceData(FAST_PAIR) == null) return
        if (record.getServiceData(FIND_MY_DEVICE) == null || result.rssi < MIN_RSSI) return
        val now = SystemClock.elapsedRealtime()
        val times = recent.getOrPut(result.device.address) { ArrayDeque() }
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > BURST_WINDOW_MS) times.removeFirst()
        if (times.size < BURST) return
        lastBurstAt = now
        if (!armed) return
        if (!open) {
            open = true
            Log.i(TAG, "lid open: ${times.size} broadcasts in ${BURST_WINDOW_MS} ms at ${result.rssi} dBm")
            onLidOpen()
            handler.postDelayed(closeCheck, 500)
        }
    }
}

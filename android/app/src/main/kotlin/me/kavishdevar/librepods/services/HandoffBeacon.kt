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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * The phone's half of the signalling for sharing AirPods with a Mac (roadmap #14; the Mac's half
 * is macos/handoff).
 *
 * Whichever of the two has the AirPods broadcasts, over Bluetooth LE, whether it is in the middle
 * of playing something. The AirPods' own broadcasts say that too, but late: after pausing on
 * Android they keep saying "music" for about five seconds, which is five seconds before the other
 * side dares take them. The beacon changes at once.
 *
 * It says one of three things: "busy" (playing, or on a call), "idle" (not playing, but the
 * person is using that device) or "away" (not playing and nobody is using it). "Away" lets the
 * AirPods move to the device in use before anything plays, so that pressing play is instant.
 * The device without the AirPods says one thing, "taking", as it starts to take them from a
 * device that is playing: connecting is about two seconds quicker when that device lets go first.
 *
 * A beacon is one 128-bit service UUID and nothing else (macOS can't broadcast more), made from
 * the AirPods' address, who is speaking and what it says, so only two devices that share those
 * AirPods recognise it: the first 16 bytes of
 * SHA-256("librepods-handoff/v1/<AirPods address>/<phone|mac>/<busy|idle|away|taking>").
 */
@SuppressLint("MissingPermission")
class HandoffBeacon(context: Context) {
    enum class State { BUSY, IDLE, AWAY, TAKING }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var advertising: UUID? = null
    private var callback: AdvertiseCallback? = null

    /** Broadcasts [state] for the AirPods at [airPods]; null stops. */
    @Synchronized
    fun advertise(airPods: MacAddress?, state: State?) {
        val uuid = if (airPods != null && state != null) uuid(airPods, "phone", state) else null
        if (uuid == advertising) return
        val advertiser = adapter?.bluetoothLeAdvertiser
        callback?.let { old -> runCatching { advertiser?.stopAdvertising(old) } }
        callback = null
        advertising = null
        if (uuid == null || advertiser == null) {
            Log.i(TAG, "beacon off")
            return
        }
        val settings = AdvertiseSettings.Builder()
            // "Away" can go on for hours with the phone in a pocket, and nothing waits on it.
            .setAdvertiseMode(if (state == State.AWAY) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(uuid))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "beacon couldn't start: error $errorCode")
            }
        }
        try {
            advertiser.startAdvertising(settings, data, cb)
            callback = cb
            advertising = uuid
            Log.i(TAG, "beacon: $state")
        } catch (e: Exception) {
            Log.w(TAG, "beacon couldn't start", e)
        }
    }

    /**
     * What the Mac that has the AirPods says, listening for at most [timeoutMs]: null if nothing
     * was heard (no helper running there, or another device has them).
     */
    suspend fun macState(airPods: MacAddress, timeoutMs: Long): State? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val states = State.entries.associateBy { uuid(airPods, "mac", it) }
        val heard = CompletableDeferred<State>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids ?: return
                uuids.firstNotNullOfOrNull { states[it.uuid] }?.let { heard.complete(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "couldn't listen for the Mac: error $errorCode")
            }
        }
        val filters = states.keys.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        return try {
            scanner.startScan(filters, settings, cb)
            withTimeoutOrNull(timeoutMs) { heard.await() }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't listen for the Mac", e)
            null
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    companion object {
        private const val TAG = "HandoffBeacon"

        fun uuid(airPods: MacAddress, role: String, state: State): UUID {
            val text = "librepods-handoff/v1/${airPods.value.uppercase()}/$role/${state.name.lowercase()}"
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val bytes = ByteBuffer.wrap(hash, 0, 16)
            return UUID(bytes.long, bytes.long)
        }
    }
}

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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

private const val TAG = "HealthConnectHeartRate"

/**
 * Writes AirPods heart rate readings to Health Connect as one entry a minute.
 *
 * The AirPods send a reading every second; writing each one as its own entry made about 3,600
 * entries an hour. Readings are collected and written as a single heart rate series when the
 * group spans a minute, when readings stop for [IDLE_FLUSH] (monitoring switched off, AirPods
 * taken out or disconnected), or when [flush] is called.
 */
class HealthConnectHeartRateWriter(
    private val context: Context,
    private val client: () -> HealthConnectClient?,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val pending = mutableListOf<HeartRateSample>()
    private var pendingInterval: Duration = 1.seconds
    private var idleFlushJob: Job? = null

    fun add(sample: HeartRateSample, interval: Duration) {
        // Health Connect only accepts 1–300 bpm; one bad value would make it reject the whole group.
        if (sample.bpm !in 1..300) return

        scope.launch {
            val ready = mutex.withLock {
                val first = pending.firstOrNull()
                val last = pending.lastOrNull()
                if (last != null && sample.timestamp == last.timestamp) return@launch

                val full = first != null && last != null && (
                    sample.timestamp - first.timestamp >= GROUP_LENGTH ||
                        sample.timestamp - last.timestamp > IDLE_FLUSH
                    )
                val finished = if (full) takePendingLocked() else null

                pending += sample
                pendingInterval = interval
                idleFlushJob?.cancel()
                idleFlushJob = scope.launch {
                    delay(IDLE_FLUSH)
                    // Once the wait is over, finish the write even if a new reading arrives.
                    withContext(NonCancellable) { flushNow() }
                }
                finished
            }
            ready?.let { (samples, interval) -> write(samples, interval) }
        }
    }

    /** Writes whatever is collected now, for example when the service stops. */
    fun flush() {
        scope.launch { withContext(NonCancellable) { flushNow() } }
    }

    private suspend fun flushNow() {
        val ready = mutex.withLock { takePendingLocked() } ?: return
        write(ready.first, ready.second)
    }

    private fun takePendingLocked(): Pair<List<HeartRateSample>, Duration>? {
        if (pending.isEmpty()) return null
        val samples = pending.toList()
        pending.clear()
        return samples to pendingInterval
    }

    private suspend fun write(samples: List<HeartRateSample>, interval: Duration) {
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) < 7) {
            Log.d(TAG, "U SDK Extension <7")
            return
        }
        if (context.checkSelfPermission(HealthPermission.getWritePermission(HeartRateRecord::class))
            != PackageManager.PERMISSION_GRANTED
        ) return
        val client = client() ?: run {
            Log.w(TAG, "Health Connect client not available")
            return
        }

        // Each reading covers the interval before it, so the entry starts one interval before the
        // first reading and ends at the last one.
        val lead = if (interval.isPositive()) interval else 1.seconds
        val startTime = (samples.minOf { it.timestamp } - lead).toJavaInstant()
        val endTime = samples.maxOf { it.timestamp }.toJavaInstant()
        val zone = ZoneOffset.systemDefault().rules

        val record = HeartRateRecord(
            startTime = startTime,
            startZoneOffset = zone.getOffset(startTime),
            endTime = endTime,
            endZoneOffset = zone.getOffset(endTime),
            samples = samples.map {
                HeartRateRecord.Sample(
                    time = it.timestamp.toJavaInstant(),
                    beatsPerMinute = it.bpm.toLong()
                )
            },
            metadata = Metadata.autoRecorded(device = Device(type = Device.TYPE_HEARABLE))
        )

        try {
            client.insertRecords(listOf(record))
            Log.d(TAG, "wrote ${samples.size} heart rate readings ($startTime to $endTime)")
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting heart rate record", e)
        }
    }

    private companion object {
        val GROUP_LENGTH = 60.seconds
        val IDLE_FLUSH = 10.seconds
    }
}

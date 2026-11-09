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

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starts [HeadTrackerService] through Shizuku and talks to it over Binder (roadmap #17). Adapted
 * from the DroidThug/librepods fork (GPL-3.0-or-later).
 */
object ShizukuBridge {
    private const val TAG = "ShizukuBridge"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile private var service: IHeadTrackerService? = null
    @Volatile private var connection: ServiceConnection? = null
    /** Called when the service's process dies (Shizuku stopped, or the phone killed it). */
    @Volatile var onLost: () -> Unit = {}
    private var lastFailureLog = 0L

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    }.getOrDefault(false)

    fun running(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun allowed(): Boolean = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure { Log.e(TAG, "couldn't ask Shizuku", it) }
    }

    val connected: Boolean get() = service != null

    /** Binds the service and creates the head tracker for [mac]. Blocks up to 5 s: call off the main thread. */
    @Synchronized
    fun start(context: Context, mac: String): Boolean {
        if (!allowed()) return false
        if (connection != null) shutdown(context) else removeStale(context)
        if (!bind(context)) return false
        val ok = runCatching { service?.start(mac) ?: false }.onFailure { Log.e(TAG, "start failed", it) }.getOrDefault(false)
        Log.i(TAG, "head tracker for the AirPods: ${if (ok) "created" else "failed"}")
        if (!ok) shutdown(context)
        return ok
    }

    fun sendPose(rx: Int, ry: Int, rz: Int, vx: Int, vy: Int, vz: Int, discontinuity: Int) {
        try {
            service?.sendPose(rx, ry, rz, vx, vy, vz, discontinuity)
        } catch (t: Throwable) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFailureLog > 1000) {
                lastFailureLog = now
                Log.w(TAG, "sendPose failed: $t")
            }
        }
    }

    /** See IHeadTrackerService.hostState; -1 also when it can't be asked. */
    fun hostState(): Int = runCatching { service?.hostState() ?: -1 }.getOrDefault(-1)

    /** Removes the head tracker and ends the service's process. */
    @Synchronized
    fun shutdown(context: Context) {
        // destroy() ends the process, so the call usually fails as the Binder dies.
        runCatching { service?.destroy() }
        val conn = connection
        connection = null
        service = null
        if (conn != null) runCatching { Shizuku.unbindUserService(args(context), conn, true) }
    }

    private fun bind(context: Context): Boolean {
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IHeadTrackerService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection !== this) return
                Log.w(TAG, "the head tracker service went away")
                service = null
                onLost()
            }
        }
        connection = conn
        return try {
            Shizuku.bindUserService(args(context), conn)
            latch.await(5, TimeUnit.SECONDS).also { if (!it) Log.e(TAG, "Shizuku didn't start the service in 5 s") }
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't bind through Shizuku", t)
            false
        }
    }

    private fun removeStale(context: Context) {
        runCatching { Shizuku.unbindUserService(args(context), null, true) }
    }

    private fun args(context: Context) = Shizuku.UserServiceArgs(ComponentName(context.packageName, HeadTrackerService::class.java.name))
        .daemon(false)
        .processNameSuffix("headtracker")
        .version(1)

    private const val REQUEST_CODE = 17
}

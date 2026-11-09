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

import android.util.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * The Shizuku user service behind [ShizukuBridge] (roadmap #17): Shizuku starts this class from
 * our APK in a process of its own with the shell's identity, which can open /dev/uhid. It holds
 * the virtual head tracker and answers the kernel's requests on its own thread.
 *
 * Adapted from the DroidThug/librepods fork (GPL-3.0-or-later). Shizuku needs a no-argument
 * constructor.
 */
class HeadTrackerService : IHeadTrackerService.Stub() {
    private val tracker = UhidHeadTracker()
    private var reader: Thread? = null

    override fun start(mac: String): Boolean {
        Log.i(TAG, "start")
        stop()
        killStaleSiblings()
        val ok = tracker.create(mac)
        if (ok) {
            reader = Thread({
                while (tracker.readEvent() != -1) Unit
            }, "uhid-events").apply { isDaemon = true; start() }
        }
        return ok
    }

    override fun sendPose(rx: Int, ry: Int, rz: Int, vx: Int, vy: Int, vz: Int, discontinuity: Int) {
        tracker.sendPose(rx, ry, rz, vx, vy, vz, discontinuity)
    }

    override fun hostState(): Int = tracker.hostState

    override fun stop() {
        tracker.destroy()
        reader?.interrupt()
        reader = null
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        stop()
        exitProcess(0)
    }

    /** Shizuku keeps these processes across app restarts; an old one may still hold a device. */
    private fun killStaleSiblings() {
        try {
            val self = android.os.Process.myPid()
            val name = File("/proc/self/cmdline").readText()
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == self) return@forEach
                val other = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
                if (other == name) {
                    Log.i(TAG, "ending an old copy, pid $pid")
                    android.os.Process.killProcess(pid)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't look for old copies", t)
        }
    }

    private companion object {
        const val TAG = "HeadTrackerService"
    }
}

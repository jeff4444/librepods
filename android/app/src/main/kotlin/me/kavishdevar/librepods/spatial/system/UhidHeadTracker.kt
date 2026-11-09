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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A virtual head tracker (roadmap #17): a HID device made through the kernel's /dev/uhid that
 * speaks Android's Head Tracker HID protocol
 * (source.android.com/docs/core/interaction/sensors/head-tracker-hid-protocol). Android's dynamic
 * sensor HAL picks it up as a TYPE_HEAD_TRACKER sensor, and the audio framework ties it to the
 * AirPods through the Bluetooth address in its persistent unique ID, so the system spatializer
 * follows the head for every app.
 *
 * Adapted from the DroidThug/librepods fork (branch shizuku-head-tracked-spatial-audio,
 * GPL-3.0-or-later). Its HID report descriptor and the bus, vendor, product and version values
 * come from fengkaobanli/O-Pose's vtracker3 helper (MIT licence; see THIRD_PARTY_NOTICES.md). The
 * uhid framing follows the kernel's include/uapi/linux/uhid.h.
 *
 * /dev/uhid is uhid:uhid 0660, so this has to run as the shell (see [HeadTrackerService]).
 */
class UhidHeadTracker {
    private companion object {
        const val TAG = "UhidHeadTracker"
        const val UHID_PATH = "/dev/uhid"

        // enum uhid_event_type
        const val UHID_DESTROY = 1
        const val UHID_START = 2
        const val UHID_STOP = 3
        const val UHID_OPEN = 4
        const val UHID_CLOSE = 5
        const val UHID_OUTPUT = 6
        const val UHID_GET_REPORT = 9
        const val UHID_GET_REPORT_REPLY = 10
        const val UHID_CREATE2 = 11
        const val UHID_INPUT2 = 12
        const val UHID_SET_REPORT = 13
        const val UHID_SET_REPORT_REPLY = 14

        // enum uhid_report_type
        const val UHID_FEATURE_REPORT = 0

        // struct uhid_create2_req (packed): name, phys, uniq, rd_size, bus, vendor, product,
        // version, country, rd_data[HID_MAX_DESCRIPTOR_SIZE].
        const val NAME_SIZE = 128
        const val PHYS_SIZE = 64
        const val UNIQ_SIZE = 64
        const val HID_MAX_DESCRIPTOR_SIZE = 4096
        const val CREATE2_SIZE = NAME_SIZE + PHYS_SIZE + UNIQ_SIZE + 2 + 2 + 4 + 4 + 4 + 4 + HID_MAX_DESCRIPTOR_SIZE
        const val UHID_DATA_MAX = 4096
        // struct uhid_event (packed): u32 type + the union, whose largest member is create2.
        const val EVENT_SIZE = 4 + CREATE2_SIZE

        const val BUS_VIRTUAL = 0x0006

        const val SENSOR_DESCRIPTION = "#AndroidHeadTracker#1.0"

        /**
         * The protocol's descriptor: report 2 (feature) holds the 23-byte sensor description and
         * the 16-byte persistent unique ID; report 1 holds the reporting state, power state and
         * report interval (feature) and the input: rotation vector 3 x int16 (+-32767 = +-pi
         * rad), angular velocity 3 x int16 (+-32767 = +-32 rad/s) and a uint8 discontinuity count.
         */
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x20, 0x09, 0xE1.toByte(), 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02, 0x0A, 0x08, 0x03, 0x15,
            0x00, 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x17, 0xB1.toByte(), 0x03, 0x0A, 0x02, 0x03,
            0x15, 0x80.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x10, 0xB1.toByte(), 0x03, 0x85.toByte(), 0x01,
            0x0A, 0x16, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x01, 0xA1.toByte(),
            0x02, 0x0A, 0x40, 0x08, 0x0A, 0x41, 0x08, 0xB1.toByte(), 0x00, 0xC0.toByte(), 0x0A, 0x19,
            0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x01, 0xA1.toByte(), 0x02, 0x0A,
            0x55, 0x08, 0x0A, 0x51, 0x08, 0xB1.toByte(), 0x00, 0xC0.toByte(), 0x0A, 0x0E, 0x03, 0x15,
            0x00, 0x25, 0x3F, 0x35, 0x0A, 0x45, 0x64, 0x75, 0x06, 0x95.toByte(), 0x01, 0x66,
            0x01, 0x10, 0x55, 0x0D, 0xB1.toByte(), 0x02, 0x0A, 0x44, 0x05, 0x16, 0x01, 0x80.toByte(),
            0x26, 0xFF.toByte(), 0x7F, 0x37, 0x60, 0x4F, 0x46, 0xED.toByte(), 0x47, 0xA1.toByte(), 0xB0.toByte(), 0xB9.toByte(),
            0x12, 0x55, 0x08, 0x75, 0x10, 0x95.toByte(), 0x03, 0x81.toByte(), 0x02, 0x0A, 0x45, 0x05,
            0x16, 0x01, 0x80.toByte(), 0x26, 0xFF.toByte(), 0x7F, 0x35, 0xE0.toByte(), 0x45, 0x20, 0x55, 0x00,
            0x75, 0x10, 0x95.toByte(), 0x03, 0x81.toByte(), 0x02, 0x0A, 0x46, 0x05, 0x15, 0x00, 0x25,
            0x7F, 0x35, 0x00, 0x45, 0x00, 0x55, 0x00, 0x75, 0x08, 0x95.toByte(), 0x01, 0x81.toByte(),
            0x02, 0xC0.toByte()
        )
    }

    private var file: RandomAccessFile? = null
    private val writeLock = Any()
    private var mac = ByteArray(6)
    private var sent = 0L

    /** What the host last set on report 1, or -1 if it hasn't (see IHeadTrackerService.hostState). */
    @Volatile var hostState = -1
        private set

    /** Opens /dev/uhid and creates the device for the AirPods at [macAddress]. */
    fun create(macAddress: String): Boolean = try {
        val f = RandomAccessFile(UHID_PATH, "rw")
        file = f
        mac = macAddress.split(":").map { it.toInt(16).toByte() }.toByteArray()
        require(mac.size == 6) { "bad address $macAddress" }
        hostState = -1
        val tag = macAddress.replace(":", "").uppercase()
        val buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(UHID_CREATE2)
        putFixedString(buf, SENSOR_DESCRIPTION, NAME_SIZE)
        putFixedString(buf, "", PHYS_SIZE)
        putFixedString(buf, "bt:$tag", UNIQ_SIZE)
        buf.putShort(DESCRIPTOR.size.toShort())
        buf.putShort(BUS_VIRTUAL.toShort())
        buf.putInt(0x1234) // vendor
        buf.putInt(0x5678) // product
        buf.putInt(0x0100) // version
        buf.putInt(0)      // country
        buf.put(DESCRIPTOR)
        write(f, buf.array())
        Log.i(TAG, "created the head tracker for bt:$tag")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "couldn't create the head tracker", t)
        close()
        false
    }

    /** Sends one input report (report 1): rotation vector, angular velocity, discontinuity count. */
    fun sendPose(rx: Int, ry: Int, rz: Int, vx: Int, vy: Int, vz: Int, discontinuity: Int): Boolean {
        val f = file ?: return false
        // The protocol says to send only once the host has turned reporting on, but some HALs
        // never set the state; reports the host isn't reading are dropped anyway.
        if (++sent % 500 == 1L) Log.i(TAG, "pose #$sent: $rx $ry $rz, rate $vx $vy $vz, discontinuity $discontinuity, host state $hostState")
        return try {
            val buf = ByteBuffer.allocate(4 + 2 + UHID_DATA_MAX).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(UHID_INPUT2)
            buf.putShort(14)
            buf.put(0x01)
            buf.putShort(rx.toShort()); buf.putShort(ry.toShort()); buf.putShort(rz.toShort())
            buf.putShort(vx.toShort()); buf.putShort(vy.toShort()); buf.putShort(vz.toShort())
            buf.put(discontinuity.toByte())
            write(f, buf.array())
            true
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't send a pose", t)
            false
        }
    }

    /**
     * Reads and answers the kernel's next event. Blocks; call from its own thread. Returns the
     * event type, or -1 once the device is gone. Android's sensor HAL reads report 2 to recognise
     * the head tracker and closes the device if nothing answers.
     */
    fun readEvent(): Int {
        val f = file ?: return -1
        return try {
            val buf = ByteArray(EVENT_SIZE)
            if (f.read(buf) <= 0) return -1
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.getInt(0)
            when (type) {
                UHID_GET_REPORT -> {
                    // struct uhid_get_report_req: u32 id, u8 rnum, u8 rtype
                    val id = bb.getInt(4)
                    val rnum = buf[8].toInt() and 0xFF
                    val rtype = buf[9].toInt() and 0xFF
                    Log.i(TAG, "host reads report $rnum (type $rtype)")
                    val data = when {
                        rtype == UHID_FEATURE_REPORT && rnum == 1 -> byteArrayOf(1, hostState.coerceAtLeast(0).toByte())
                        rtype == UHID_FEATURE_REPORT && rnum == 2 -> byteArrayOf(2) + SENSOR_DESCRIPTION.toByteArray(Charsets.US_ASCII) + uniqueId()
                        else -> ByteArray(0)
                    }
                    val reply = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    reply.putInt(UHID_GET_REPORT_REPLY)
                    reply.putInt(id)
                    reply.putShort(0)
                    reply.putShort(data.size.toShort())
                    reply.put(data)
                    write(f, reply.array())
                }
                UHID_SET_REPORT -> {
                    // struct uhid_set_report_req: u32 id, u8 rnum, u8 rtype, u16 size, u8 data[]
                    val id = bb.getInt(4)
                    val rnum = buf[8].toInt() and 0xFF
                    val rtype = buf[9].toInt() and 0xFF
                    val size = bb.getShort(10).toInt() and 0xFFFF
                    if (rtype == UHID_FEATURE_REPORT && rnum == 1 && size >= 2) {
                        hostState = buf[13].toInt() and 0xFF
                        Log.i(TAG, "host set reporting ${if (hostState and 1 != 0) "on" else "off"}, power ${if (hostState and 2 != 0) "full" else "off"}, interval ${hostState shr 2}")
                    } else {
                        Log.i(TAG, "host wrote report $rnum (type $rtype, $size bytes)")
                    }
                    val reply = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    reply.putInt(UHID_SET_REPORT_REPLY)
                    reply.putInt(id)
                    reply.putShort(0)
                    write(f, reply.array())
                }
                UHID_START -> Log.i(TAG, "started")
                UHID_STOP -> Log.i(TAG, "stopped")
                UHID_OPEN -> Log.i(TAG, "opened by the host")
                UHID_CLOSE -> Log.i(TAG, "closed by the host")
                UHID_OUTPUT -> Log.i(TAG, "host sent an output report")
                else -> Log.i(TAG, "event $type")
            }
            type
        } catch (t: Throwable) {
            if (file != null) Log.e(TAG, "couldn't read an event", t)
            -1
        }
    }

    /** Persistent unique ID: 8 zero bytes, "BT", then the Bluetooth address. */
    private fun uniqueId(): ByteArray {
        val out = ByteArray(16)
        out[8] = 'B'.code.toByte()
        out[9] = 'T'.code.toByte()
        mac.copyInto(out, 10)
        return out
    }

    fun destroy() {
        try {
            file?.let { f -> write(f, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array()) }
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't destroy the device", t)
        } finally {
            close()
        }
    }

    private fun close() {
        val f = file
        file = null
        runCatching { f?.close() }
    }

    private fun write(f: RandomAccessFile, bytes: ByteArray) = synchronized(writeLock) { f.write(bytes) }

    private fun putFixedString(buf: ByteBuffer, s: String, size: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, size)
        buf.put(bytes, 0, n)
        repeat(size - n) { buf.put(0) }
    }
}

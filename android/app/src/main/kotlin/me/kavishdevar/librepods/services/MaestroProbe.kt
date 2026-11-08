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
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import kotlin.time.Duration.Companion.seconds

/**
 * Debug probe for Pixel Buds (roadmap #15): opens the buds' Maestro channel, the RFCOMM service
 * Google's Pixel Buds app uses, sends a few read-only calls and logs every frame that comes back,
 * under the tag "MaestroProbe". Answers whether LibrePods can talk to the buds while Google's app
 * holds the same channel.
 *
 * Wire format (from pbpctrl's libmaestro): pw_rpc RpcPacket protobufs in HDLC U-frames. The HDLC
 * address carries the two peers, source in bits 6-9 and target in bits 10-13 (MaestroA = 10,
 * left bud = 3, right bud = 4, case = 2), and the RpcPacket's channel id names the same pair
 * (19 = MaestroA to the left bud, 21 = to the right one, 18 = to the case).
 */
object MaestroProbe {
    private const val TAG = "MaestroProbe"
    private val MAESTRO_UUID: UUID = UUID.fromString("25e97ff7-24ce-4c4c-8951-f764a708f7b5")

    private data class Channel(val id: Int, val address: Int, val name: String)

    // Host slot A (peer 10, channels 18-22) is what Google's app uses; slot B (peer 13, channels
    // 23-27) is the second one in pbpctrl's address map.
    private fun channels(hostB: Boolean): List<Channel> {
        val host = if (hostB) 13 else 10
        val base = if (hostB) 23 else 18
        return listOf(
            Channel(base + 1, (host shl 6) or (3 shl 10), "left"),
            Channel(base + 3, (host shl 6) or (4 shl 10), "right"),
            Channel(base, (host shl 6) or (2 shl 10), "case"),
        )
    }

    /**
     * [uuid] picks the RFCOMM service (default: Maestro, `…f7b5`; the buds also offer `…f7b4`), and
     * [hostB] addresses the calls from host slot B instead of A.
     */
    @SuppressLint("MissingPermission")
    fun run(context: Context, scope: CoroutineScope, seconds: Int, uuid: UUID = MAESTRO_UUID, hostB: Boolean = false) {
        val CHANNELS = channels(hostB)
        scope.launch(Dispatchers.IO) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@launch
            val buds = adapter.bondedDevices.firstOrNull { device ->
                device.uuids?.any { it.uuid == MAESTRO_UUID || it.uuid == uuid } == true
            }
            if (buds == null) {
                Log.w(TAG, "no bonded device offers the Maestro service")
                return@launch
            }
            Log.i(TAG, "opening $uuid on ${buds.name}, host slot ${if (hostB) "B" else "A"}")
            val socket = try {
                buds.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
            } catch (e: Exception) {
                Log.w(TAG, "couldn't open the Maestro channel: ${e.message}")
                return@launch
            }
            Log.i(TAG, "connected")
            if (uuid != MAESTRO_UUID) {
                // Another channel (Google's Fast Pair message stream, say): send nothing, log what comes.
                // Fast Pair messages are [group][code][length: 2 bytes, big-endian][data].
                // A blocked Bluetooth read only returns when the socket closes.
                val closer = launch { delay(seconds.seconds); runCatching { socket.close() } }
                try {
                    val input = socket.inputStream
                    val buf = ByteArray(1024)
                    val pending = ByteArrayOutputStream()
                    run {
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            Log.i(TAG, "raw ${buf.copyOf(n).hex()}")
                            pending.write(buf, 0, n)
                            var bytes = pending.toByteArray()
                            while (bytes.size >= 4) {
                                val length = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                                if (bytes.size < 4 + length) break
                                Log.i(TAG, "message group ${bytes[0].toInt() and 0xFF} code ${bytes[1].toInt() and 0xFF}: ${bytes.copyOfRange(4, 4 + length).hex()}")
                                bytes = bytes.copyOfRange(4 + length, bytes.size)
                            }
                            pending.reset(); pending.write(bytes)
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "reader stopped: ${e.message}")
                } finally {
                    closer.cancel()
                    runCatching { socket.close() }
                    Log.i(TAG, "closed")
                }
                return@launch
            }
            val reader = launch(Dispatchers.IO) {
                val input = socket.inputStream
                val frame = ByteArrayOutputStream()
                var escape = false
                val buf = ByteArray(1024)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        for (i in 0 until n) {
                            var b = buf[i].toInt() and 0xFF
                            when {
                                b == 0x7E -> {
                                    if (frame.size() > 0) Log.i(TAG, "frame ${frame.toByteArray().hex()}")
                                    frame.reset()
                                    escape = false
                                }
                                b == 0x7D -> escape = true
                                else -> {
                                    if (escape) {
                                        b = b xor 0x20
                                        escape = false
                                    }
                                    frame.write(b)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "reader stopped: ${e.message}")
                }
            }
            try {
                val output = socket.outputStream
                for (channel in CHANNELS) {
                    for (method in listOf("GetSoftwareInfo", "GetHardwareInfo")) {
                        Log.i(TAG, "-> ${channel.name} Maestro.$method")
                        output.write(hdlcFrame(channel.address, rpcRequest(channel.id, "maestro_pw.Maestro", method)))
                        output.flush()
                        delay(300)
                    }
                }
                // Current noise control mode (setting 13), as Google's app reads it.
                for (channel in CHANNELS.take(2)) {
                    Log.i(TAG, "-> ${channel.name} Maestro.ReadSetting 13")
                    output.write(hdlcFrame(channel.address, rpcRequest(channel.id, "maestro_pw.Maestro", "ReadSetting", byteArrayOf(0x20, 13))))
                    output.flush()
                    delay(300)
                }
                delay(seconds.seconds)
            } catch (e: Exception) {
                Log.w(TAG, "write failed: ${e.message}")
            } finally {
                runCatching { socket.close() }
                reader.cancel()
                Log.i(TAG, "closed")
            }
        }
    }

    /** pw_rpc's 65599 hash of a service or method name. */
    private fun pwHash(name: String): Int {
        var hash = name.length
        var coef = 65599
        for (c in name) {
            hash += coef * c.code
            coef *= 65599
        }
        return hash
    }

    private fun rpcRequest(channel: Int, service: String, method: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x10) // field 2, channel_id (type 1 = REQUEST is 0, left out)
        writeVarint(out, channel)
        out.write(0x1D) // field 3, service_id, fixed32
        writeFixed32(out, pwHash(service))
        out.write(0x25) // field 4, method_id, fixed32
        writeFixed32(out, pwHash(method))
        if (payload.isNotEmpty()) {
            out.write(0x2A) // field 5, payload
            writeVarint(out, payload.size)
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun hdlcFrame(address: Int, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        // Address: a varint whose last byte has its lowest bit set.
        var a = address
        while (true) {
            val b = (a and 0x7F) shl 1
            a = a ushr 7
            if (a == 0) {
                body.write(b or 1)
                break
            }
            body.write(b)
        }
        body.write(0x03) // UI frame
        body.write(payload)
        val bytes = body.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value
        val framed = bytes + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte())
        val out = ByteArrayOutputStream()
        out.write(0x7E)
        for (x in framed) {
            val v = x.toInt() and 0xFF
            if (v == 0x7E || v == 0x7D) {
                out.write(0x7D)
                out.write(v xor 0x20)
            } else {
                out.write(v)
            }
        }
        out.write(0x7E)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun writeFixed32(out: ByteArrayOutputStream, value: Int) {
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

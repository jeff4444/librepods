package me.kavishdevar.librepods.core.bluetooth.maestro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

private const val TAG = "Maestro"

/**
 * Google's "Maestro" protocol for Pixel Buds: Pigweed RPC (pw_rpc) packets, protobuf-encoded,
 * inside HDLC U-frames, over an RFCOMM channel. Layout from pbpctrl's libmaestro; message
 * contents from captures of Google's Pixel Buds app (research/pixelbuds).
 *
 * Only one app can hold the channel: while Google's Pixel Buds app is installed and connected,
 * [connect] fails.
 */
class MaestroClient(private val device: BluetoothDevice) {
    companion object {
        val UUID: UUID = java.util.UUID.fromString("25e97ff7-24ce-4c4c-8951-f764a708f7b5")

        const val MAESTRO = "maestro_pw.Maestro"

        // pw_rpc packet types
        const val REQUEST = 0
        const val RESPONSE = 1
        const val SERVER_ERROR = 5
        const val SERVER_STREAM = 7

        /** pw_rpc's 65599 hash of a service or method name. */
        fun hash(name: String): Int {
            var hash = name.length
            var coef = 65599
            for (c in name) {
                hash += coef * c.code
                coef *= 65599
            }
            return hash
        }
    }

    /** A host-to-bud route: the RpcPacket channel id and the HDLC address of the same pair. */
    enum class Peer(val channel: Int, target: Int) {
        CASE(18, 2),
        LEFT(19, 3),
        RIGHT(21, 4);

        // Source = host slot "MaestroA" (10) in bits 6-9, target in bits 10-13.
        val address: Int = (10 shl 6) or (target shl 10)
    }

    data class Packet(
        val type: Int,
        val channel: Int,
        val service: Int,
        val method: Int,
        val payload: ByteArray,
        val status: Int,
    )

    private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    private val pending = ConcurrentHashMap<Triple<Int, Int, Int>, CompletableDeferred<Packet>>()
    private val streams = ConcurrentHashMap<Triple<Int, Int, Int>, (Packet) -> Unit>()

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Blocking. False when the channel can't be opened, e.g. because another app holds it. */
    @SuppressLint("MissingPermission")
    fun connect(scope: CoroutineScope, onClosed: () -> Unit): Boolean {
        if (isConnected) return true
        val s = try {
            device.createRfcommSocketToServiceRecord(UUID).also { it.connect() }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open the Maestro channel: ${e.message}")
            return false
        }
        socket = s
        reader = scope.launch(Dispatchers.IO) {
            val frame = ByteArrayOutputStream()
            var escape = false
            val buf = ByteArray(2048)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        var b = buf[i].toInt() and 0xFF
                        when {
                            b == 0x7E -> {
                                if (frame.size() > 0) onFrame(frame.toByteArray())
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
                Log.i(TAG, "channel closed: ${e.message}")
            }
            socket = null
            pending.values.forEach { it.cancel() }
            pending.clear()
            streams.clear()
            onClosed()
        }
        Log.i(TAG, "connected")
        return true
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        reader?.cancel()
    }

    /** A unary call; null on timeout, error status or a closed channel. */
    suspend fun call(peer: Peer, method: String, payload: ByteArray = ByteArray(0), service: String = MAESTRO): Packet? {
        val key = Triple(peer.channel, hash(service), hash(method))
        val deferred = CompletableDeferred<Packet>()
        pending[key] = deferred
        if (!send(peer, key, payload)) {
            pending.remove(key)
            return null
        }
        val packet = withTimeoutOrNull(3_000) { runCatching { deferred.await() }.getOrNull() }
        pending.remove(key)
        return packet?.takeIf { it.status == 0 }
    }

    /** Opens a server stream; [onMessage] gets each message's payload until the channel closes. */
    fun subscribe(peer: Peer, method: String, service: String = MAESTRO, payload: ByteArray = ByteArray(0), onMessage: (ByteArray) -> Unit): Boolean {
        val key = Triple(peer.channel, hash(service), hash(method))
        streams[key] = { packet -> onMessage(packet.payload) }
        return send(peer, key, payload)
    }

    private fun send(peer: Peer, key: Triple<Int, Int, Int>, payload: ByteArray): Boolean {
        val out = socket?.outputStream ?: return false
        val rpc = ByteArrayOutputStream().apply {
            write(0x10); writeVarint(this, key.first)     // channel_id (type REQUEST = 0 omitted)
            write(0x1D); writeFixed32(this, key.second)  // service_id
            write(0x25); writeFixed32(this, key.third)   // method_id
            if (payload.isNotEmpty()) {
                write(0x2A); writeVarint(this, payload.size); write(payload)
            }
        }.toByteArray()
        return try {
            synchronized(out) {
                out.write(hdlcFrame(peer.address, rpc))
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            false
        }
    }

    private fun onFrame(frame: ByteArray) {
        if (frame.size < 6) return
        val body = frame.copyOfRange(0, frame.size - 4)
        val crc = CRC32().apply { update(body) }.value
        val got = (frame[frame.size - 4].toLong() and 0xFF) or ((frame[frame.size - 3].toLong() and 0xFF) shl 8) or
            ((frame[frame.size - 2].toLong() and 0xFF) shl 16) or ((frame[frame.size - 1].toLong() and 0xFF) shl 24)
        if (crc != got) return
        var i = 0
        while (i < body.size && body[i].toInt() and 1 == 0) i++  // address: one-terminated varint
        i++
        if (i >= body.size || body[i].toInt() != 0x03) return       // UI frames only
        val fields = Proto.parse(body.copyOfRange(i + 1, body.size)) ?: return
        val packet = Packet(
            type = fields.int(1) ?: 0,
            channel = fields.int(2) ?: 0,
            service = fields.fixed32(3) ?: 0,
            method = fields.fixed32(4) ?: 0,
            payload = fields.bytes(5) ?: ByteArray(0),
            status = fields.int(6) ?: 0,
        )
        val key = Triple(packet.channel, packet.service, packet.method)
        when (packet.type) {
            SERVER_STREAM -> streams[key]?.invoke(packet)
            RESPONSE, SERVER_ERROR -> {
                val waiting = pending[key]
                if (waiting != null) waiting.complete(packet)
                else if (packet.type == SERVER_ERROR) streams.remove(key)
            }
        }
    }

    private fun hdlcFrame(address: Int, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
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
        body.write(0x03)
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
}

/** Just enough protobuf to read Maestro messages without their .proto files. */
object Proto {
    class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?)

    class Message(val fields: List<Field>) {
        fun all(number: Int) = fields.filter { it.number == number }
        fun int(number: Int): Int? = fields.lastOrNull { it.number == number && it.wireType == 0 }?.varint?.toInt()
        fun bool(number: Int): Boolean? = int(number)?.let { it != 0 }
        fun fixed32(number: Int): Int? = fields.lastOrNull { it.number == number && it.wireType == 5 }?.varint?.toInt()
        fun float(number: Int): Float? = fixed32(number)?.let { java.lang.Float.intBitsToFloat(it) }
        fun bytes(number: Int): ByteArray? = fields.lastOrNull { it.number == number && it.wireType == 2 }?.bytes
        fun string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)
        fun message(number: Int): Message? = bytes(number)?.let(::parse)
    }

    fun parse(b: ByteArray): Message? {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                if (i >= b.size) throw IllegalArgumentException("truncated varint")
                val c = b[i++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                shift += 7
                if (c and 0x80 == 0) return v
            }
        }
        return try {
            while (i < b.size) {
                val key = varint()
                val number = (key ushr 3).toInt()
                val type = (key and 7).toInt()
                if (number == 0) return null
                when (type) {
                    0 -> out += Field(number, 0, varint(), null)
                    1 -> {
                        if (i + 8 > b.size) return null
                        var v = 0L
                        for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                        i += 8
                        out += Field(number, 1, v, null)
                    }
                    2 -> {
                        val n = varint().toInt()
                        if (n < 0 || i + n > b.size) return null
                        out += Field(number, 2, 0, b.copyOfRange(i, i + n))
                        i += n
                    }
                    5 -> {
                        if (i + 4 > b.size) return null
                        var v = 0L
                        for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                        i += 4
                        out += Field(number, 5, v, null)
                    }
                    else -> return null
                }
            }
            Message(out)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** A message holding one varint field: how a setting id or bool value is sent. */
    fun varintField(number: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(number shl 3)
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
        return out.toByteArray()
    }
}

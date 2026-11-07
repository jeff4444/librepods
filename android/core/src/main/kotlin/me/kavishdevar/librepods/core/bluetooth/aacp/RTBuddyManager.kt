package me.kavishdevar.librepods.core.bluetooth.aacp

import android.util.Log
import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.RequestAllDescriptors
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceSetting
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.devices.PacketDestination
import kotlin.time.Duration

class RTBuddyManager(
    private val sendPacket: (AACPPacket) -> Boolean,
) {
    companion object {
        private const val TAG = "RTBuddyManager"
    }

    private var sequence = 0

    private fun nextSequence(): Int {
        val next = sequence
        sequence = (sequence + 1) and 0x7FFFFFFF
        return next
    }

    private fun observeSequence(data: SensorDataWX) {
        Log.d(TAG, "Received SensorDataWX seq=${data.seq}")
    }

    fun handlePacket(packet: RTBuddyPacket) {
        when (val payload = packet.rtBuddyPayload) {
            is SensorDataWxBuddyPayload -> {
                val data = payload.data
                observeSequence(data)
            }

            else -> {
                Log.d(
                    TAG,
                    "Unhandled RTBuddy payload: ${payload.descriptor}"
                )
            }
        }
    }

    fun sendSensorData(
        data: SensorDataWX,
    ): Boolean {
        val dataWithSequence = data.toBuilder()
            .setSeq(nextSequence())
            .build()

        val packet = RTBuddyPacket.create(
            rtBuddyPayload = SensorDataWxBuddyPayload(
                data = dataWithSequence,
            ),
            destination = PacketDestination.DEVICE,
        )

        return sendPacket(packet)
    }

    private fun sendSensorServiceSetting(
        service: SensorServiceType,
        configuration: ByteArray,
    ): Boolean {
        val data = SensorDataWX.newBuilder()
            .setServiceSettings(
                SensorServiceSetting.newBuilder()
                    .setService(service)
                    .setSetting(2)
                    .setConfiguration(
                        ByteString.copyFrom(configuration)
                    )
            )
            .build()

        return sendSensorData(data)
    }

    fun setSensorServiceReportInterval(sensorServiceType: SensorServiceType, interval: Duration): Boolean = sendSensorServiceSetting(
        service = sensorServiceType,
        configuration = byteArrayOf(0x01) + interval.toMicrosUINT32LE()
    )

    /**
     * Starts (or with a zero [interval] stops) the sensor service with this raw ID, including IDs
     * the schema doesn't name. Used to explore what the AirPods can stream (roadmap #8).
     */
    fun setSensorServiceReportInterval(serviceId: Int, interval: Duration): Boolean {
        val data = SensorDataWX.newBuilder()
            .setServiceSettings(
                SensorServiceSetting.newBuilder()
                    .setServiceValue(serviceId)
                    .setSetting(2)
                    .setConfiguration(
                        ByteString.copyFrom(byteArrayOf(0x01) + interval.toMicrosUINT32LE())
                    )
            )
            .build()
        return sendSensorData(data)
    }

    /** Sends any sensor service setting with any configuration bytes (roadmap #8 exploration). */
    fun sendRawSensorSetting(serviceId: Int, setting: Int, configuration: ByteArray): Boolean = sendSensorData(
        SensorDataWX.newBuilder()
            .setServiceSettings(
                SensorServiceSetting.newBuilder()
                    .setServiceValue(serviceId)
                    .setSetting(setting)
                    .setConfiguration(ByteString.copyFrom(configuration))
            )
            .build()
    )

    /** Asks the AirPods to describe every sensor service they have; each answers with a sensor_descriptor. */
    fun requestAllDescriptors(): Boolean = sendSensorData(
        SensorDataWX.newBuilder()
            .setRequestAllDescriptors(RequestAllDescriptors.getDefaultInstance())
            .build()
    )
}

private fun Duration.toMicrosUINT32LE(): ByteArray {
    val microseconds = this.inWholeMicroseconds
    return byteArrayOf(
        microseconds.toByte(),
        (microseconds shr 8).toByte(),
        (microseconds shr 16).toByte(),
        (microseconds shr 24).toByte()
    )
}

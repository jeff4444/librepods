package me.kavishdevar.librepods.core.data.apple

import me.kavishdevar.librepods.core.devices.DeviceComponent

/** Which Bluetooth LE advert a signal reading came from. */
enum class SignalSource {
    /** The buds' proximity advert, sent by one bud (see [SignalReading.bud]). */
    BUDS,

    /** The charging case's own advert (AirPods Pro 2 and later). */
    CASE
}

/**
 * One received advert's signal strength, for finding lost AirPods by walking towards a stronger
 * signal.
 *
 * @param rssi received signal strength in dBm, as reported by the phone's Bluetooth chip
 * @param bud for [SignalSource.BUDS], the bud the advert says is primary, which is the one sending it
 */
data class SignalReading(
    val source: SignalSource,
    val rssi: Int,
    val timeMillis: Long,
    val bud: DeviceComponent? = null,
)

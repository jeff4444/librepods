package me.kavishdevar.librepods.core.data.apple

import kotlin.math.sqrt

/** A rotation as a unit quaternion. */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    fun conjugate() = Quaternion(w, -x, -y, -z)

    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    /** Rotates a vector by this quaternion. */
    fun rotate(v: FloatArray): FloatArray {
        val r = this * Quaternion(0f, v[0], v[1], v[2]) * conjugate()
        return floatArrayOf(r.x, r.y, r.z)
    }

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /** From x, y, z with w implied (w >= 0), as the AirPods send it. */
        fun fromXyz(x: Float, y: Float, z: Float) = Quaternion(sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f)), x, y, z)
    }
}

/**
 * One head-motion sample from the AirPods' DEVMOTION6 stream (roadmap #9 decode).
 *
 * @param timestampMicros the AirPods' clock, microseconds, wraps at 2^32
 * @param orientation the bud's fused orientation
 * @param rotationRate the bud's raw rotation rate in rad/s (x, y, z in the bud's frame)
 */
data class MotionSample(
    val timestampMicros: Long,
    val orientation: Quaternion,
    val rotationRate: FloatArray,
)

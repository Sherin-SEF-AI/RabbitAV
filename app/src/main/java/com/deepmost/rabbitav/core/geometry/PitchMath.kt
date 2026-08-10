package com.deepmost.rabbitav.core.geometry

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Camera pitch from a stationary gravity sample. The camera optical axis is
 * device -Z; positive pitch = looking down toward the road. Shared by the
 * calibration wizard and the drive-start drift check (Section 5.9), and
 * unit-tested against synthetic gravity vectors.
 */
object PitchMath {
    /** Returns radians, or NaN when the sample is not a plausible gravity vector. */
    fun pitchFromGravity(ax: Float, ay: Float, az: Float): Float {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        if (mag < 5f || mag > 15f) return Float.NaN
        return asin((-az / mag).coerceIn(-1f, 1f))
    }
}

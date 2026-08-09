package com.deepmost.rabbitav.core.imu

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reorients raw device-frame IMU into the vehicle frame (Section 5.6):
 * gravity direction from a 2 s low-pass gives "up"; the forward axis is
 * learned by correlating horizontal specific force with GPS speed changes
 * during acceleration/braking. Adaptation is slow and FROZEN during jolt
 * windows so the jolt itself cannot corrupt the alignment.
 *
 * Pure Kotlin; the pipeline feeds it samples, tests feed it synthetics.
 */
class VehicleFrameAligner {

    // gravity low-pass state (device frame)
    private var gx = 0f
    private var gy = 0f
    private var gz = 9.81f
    private var haveGravity = false

    // learned forward unit vector (device frame, horizontal)
    private var fwdX = 0f
    private var fwdY = 0f
    private var fwdZ = 0f
    private var fwdWeight = 0f // grows toward 1 as evidence accumulates

    /** Set true while a jolt window is being captured. */
    @Volatile var freezeAdaptation = false

    /** Longitudinal ego acceleration (from GPS speed derivative), m/s^2. */
    @Volatile var egoAccelMps2 = 0f

    val hasForwardAxis: Boolean get() = fwdWeight > 0.3f
    val gravityMagnitude: Float get() = sqrt(gx * gx + gy * gy + gz * gz)

    /**
     * @param dtS sample interval
     * @return vertical specific-force deviation (m/s^2, +up), BEFORE band-pass
     */
    fun processAccel(ax: Float, ay: Float, az: Float, dtS: Float): Float {
        if (!haveGravity) {
            gx = ax; gy = ay; gz = az
            haveGravity = true
        } else if (!freezeAdaptation) {
            // tau 2 s gravity tracker (Section 5.6)
            val alpha = (dtS / (GRAVITY_TAU_S + dtS)).coerceIn(0f, 0.5f)
            gx += alpha * (ax - gx)
            gy += alpha * (ay - gy)
            gz += alpha * (az - gz)
        }
        val gMag = gravityMagnitude
        if (gMag < 1f) return 0f
        val ux = gx / gMag
        val uy = gy / gMag
        val uz = gz / gMag

        // vertical channel: projection onto "up" minus steady gravity
        val vertical = ax * ux + ay * uy + az * uz - gMag

        // forward-axis learning from horizontal accel vs GPS accel correlation
        if (!freezeAdaptation && abs(egoAccelMps2) > MIN_EGO_ACCEL_MPS2) {
            var hx = ax - (ax * ux + ay * uy + az * uz) * ux
            var hy = ay - (ax * ux + ay * uy + az * uz) * uy
            var hz = az - (ax * ux + ay * uy + az * uz) * uz
            val hMag = sqrt(hx * hx + hy * hy + hz * hz)
            if (hMag > MIN_HORIZONTAL_MPS2) {
                // While accelerating, horizontal specific force points forward;
                // while braking, backward — flip so evidence always votes forward.
                val sign = if (egoAccelMps2 >= 0f) 1f else -1f
                hx = sign * hx / hMag
                hy = sign * hy / hMag
                hz = sign * hz / hMag
                fwdX += FWD_ALPHA * (hx - fwdX)
                fwdY += FWD_ALPHA * (hy - fwdY)
                fwdZ += FWD_ALPHA * (hz - fwdZ)
                val m = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
                fwdWeight = m // coherent evidence -> magnitude toward 1
            }
        }
        return vertical
    }

    /**
     * Pitch rate = gyro component about the vehicle's lateral axis
     * (lateral = up x forward). Returns NaN until the forward axis is learned.
     */
    fun pitchRate(gxr: Float, gyr: Float, gzr: Float): Float {
        if (!hasForwardAxis) return Float.NaN
        val gMag = gravityMagnitude
        if (gMag < 1f) return Float.NaN
        val ux = gx / gMag
        val uy = gy / gMag
        val uz = gz / gMag
        val fm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
        if (fm < 1e-3f) return Float.NaN
        val fx = fwdX / fm
        val fy = fwdY / fm
        val fz = fwdZ / fm
        // lateral = up x forward
        val lx = uy * fz - uz * fy
        val ly = uz * fx - ux * fz
        val lz = ux * fy - uy * fx
        return gxr * lx + gyr * ly + gzr * lz
    }

    fun reset() {
        haveGravity = false
        fwdX = 0f; fwdY = 0f; fwdZ = 0f
        fwdWeight = 0f
    }

    companion object {
        /** Gravity LP time constant (Section 5.6): 2 s. */
        const val GRAVITY_TAU_S = 2.0f

        /** Ego accel needed before forward-axis evidence counts. ~hard-ish
         *  acceleration/braking; range 0.5-1.5. */
        const val MIN_EGO_ACCEL_MPS2 = 0.8f

        /** Horizontal specific force floor for a usable direction sample. */
        const val MIN_HORIZONTAL_MPS2 = 0.5f

        /** Slow adaptation per qualifying sample (~seconds of maneuvers). */
        const val FWD_ALPHA = 0.02f
    }
}

package com.deepmost.rabbitav.core.imu

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2nd-order Butterworth biquad (RBJ cookbook, bilinear transform), Direct Form
 * II Transposed. No DSP dependency (Section 5.6). Unit-tested for magnitude
 * response in ButterworthTest.
 */
class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    companion object {
        private val Q = (1.0 / sqrt(2.0)).toFloat() // Butterworth Q

        fun lowPass(cutoffHz: Float, fsHz: Float): Biquad {
            val w = 2f * PI.toFloat() * cutoffHz / fsHz
            val alpha = sin(w) / (2f * Q)
            val cw = cos(w)
            val a0 = 1f + alpha
            return Biquad(
                b0 = ((1f - cw) / 2f) / a0,
                b1 = (1f - cw) / a0,
                b2 = ((1f - cw) / 2f) / a0,
                a1 = (-2f * cw) / a0,
                a2 = (1f - alpha) / a0,
            )
        }

        fun highPass(cutoffHz: Float, fsHz: Float): Biquad {
            val w = 2f * PI.toFloat() * cutoffHz / fsHz
            val alpha = sin(w) / (2f * Q)
            val cw = cos(w)
            val a0 = 1f + alpha
            return Biquad(
                b0 = ((1f + cw) / 2f) / a0,
                b1 = (-(1f + cw)) / a0,
                b2 = ((1f + cw) / 2f) / a0,
                a1 = (-2f * cw) / a0,
                a2 = (1f - alpha) / a0,
            )
        }
    }
}

/**
 * The Section 5.6 vertical channel band-pass: HP 0.8 Hz (kills gravity drift
 * and slow pitch) cascaded with LP 30 Hz (kills engine/road buzz). Designed at
 * the MEASURED sample rate; the LP cutoff clamps to 0.4*fs on slow sensors.
 */
class BandPass(fsHz: Float) {
    private val hp = Biquad.highPass(HIGH_PASS_HZ, fsHz)
    private val lp = Biquad.lowPass(minOf(LOW_PASS_HZ, 0.4f * fsHz), fsHz)

    fun process(x: Float): Float = lp.process(hp.process(x))

    fun reset() {
        hp.reset()
        lp.reset()
    }

    companion object {
        /** Band edges (Section 5.6): 0.8-30 Hz. */
        const val HIGH_PASS_HZ = 0.8f
        const val LOW_PASS_HZ = 30f
    }
}

package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.tracking.PvKalman
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 gate: TTC filter convergence. */
class PvKalmanTest {

    @Test
    fun convergesOnConstantClosingSpeed() {
        // Target approaches from 50 m at 5 m/s; measurements at 25 Hz with
        // distance-proportional noise, like the real Z pipeline.
        val kalman = PvKalman(processNoise = 6f, measurementNoise = 4f)
        val rng = Random(42)
        val dt = 0.04f
        var trueZ = 50f
        for (i in 0 until 75) { // 3 s
            trueZ -= 5f * dt
            kalman.predict(dt)
            val sigma = trueZ * 0.08f
            kalman.update(trueZ + rng.nextGaussian().toFloat() * sigma, sigma * sigma)
        }
        val vc = -kalman.v
        assertEquals("closing speed", 5f, vc, 1.2f)
        assertEquals("distance", trueZ, kalman.p, 2.5f)

        val ttc = kalman.p / vc
        val trueTtc = trueZ / 5f
        assertTrue("ttc $ttc vs $trueTtc", abs(ttc - trueTtc) < 2f)
    }

    @Test
    fun steadyDistanceGivesNearZeroVelocity() {
        val kalman = PvKalman(6f, 4f)
        val rng = Random(7)
        for (i in 0 until 100) {
            kalman.predict(0.04f)
            kalman.update(30f + rng.nextGaussian().toFloat() * 1.5f, 2.25f)
        }
        assertTrue("velocity ${kalman.v}", abs(kalman.v) < 0.8f)
    }

    @Test
    fun predictWithoutUpdateCoasts() {
        val kalman = PvKalman(0.35f, 0.004f)
        kalman.reset(0.5f, 0.1f)
        kalman.predict(1f)
        assertEquals(0.6f, kalman.p, 1e-4f)
    }
}

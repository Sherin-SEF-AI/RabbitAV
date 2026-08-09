package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.geometry.CalibrationState
import com.deepmost.rabbitav.core.geometry.CameraIntrinsics
import com.deepmost.rabbitav.core.geometry.GroundGeometry
import com.deepmost.rabbitav.core.geometry.VehiclePreset
import com.deepmost.rabbitav.core.inference.CanonicalClass
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 gate: IPM with known geometry in -> known distance out. */
class GeometryTest {

    private val intrinsics = CameraIntrinsics(
        fx = 500f, fy = 500f, cx = 320f, cy = 240f,
        width = 640, height = 480, source = CameraIntrinsics.Source.FOV_FALLBACK,
    )
    private val calib = CalibrationState(
        valid = true, preset = VehiclePreset.SEDAN,
        cameraHeightM = 1.30f, pitchRad = Math.toRadians(2.0).toFloat(),
    )
    private val geo = GroundGeometry(intrinsics, calib)

    @Test
    fun ipmKnownGeometry() {
        // Hand-computed: Z = 20 m, h = 1.3, theta = 2 deg.
        // phi = atan(h/Z) = atan(0.065) = 3.7185 deg
        // v = cy + fy * tan(phi - theta) = 240 + 500*tan(1.7185 deg) = 255.0 px
        val z = 20f
        val phi = atan(calib.cameraHeightM / z)
        val vPx = intrinsics.cy + intrinsics.fy * tan(phi - calib.pitchRad)
        assertEquals(255.0f, vPx, 0.5f)

        val zBack = geo.ipmDistance(vPx / intrinsics.height)
        assertEquals(z, zBack, 0.05f)
    }

    @Test
    fun ipmRoundTripAcrossRange() {
        for (z in listOf(5f, 10f, 25f, 50f, 80f)) {
            val v = geo.vForDistance(z)
            val zBack = geo.ipmDistance(v)
            assertEquals("round trip at $z m", z, zBack, z * 0.01f)
        }
    }

    @Test
    fun ipmRejectsAboveHorizon() {
        val horizonV = geo.horizonV() / intrinsics.height
        assertTrue(geo.ipmDistance(horizonV - 0.05f).isNaN())
    }

    @Test
    fun widthPriorKnownGeometry() {
        // Zw = fx * W / w_px: car (1.75 m) 50 px wide -> 500*1.75/50 = 17.5 m
        val z = geo.widthPriorDistance(CanonicalClass.CAR, 50f / 640f)
        assertEquals(17.5f, z, 0.01f)
    }

    @Test
    fun lateralOffsetSignsAndMagnitude() {
        // 100 px right of center at Z=20: X = 20 * 100/500 = 4 m
        val x = geo.lateralOffset(20f, (320f + 100f) / 640f)
        assertEquals(4f, x, 0.01f)
        assertTrue(geo.lateralOffset(20f, (320f - 100f) / 640f) < 0f)
    }

    @Test
    fun corridorGates() {
        assertTrue(geo.isInCorridor(CanonicalClass.CAR, 1.9f))
        assertFalse(geo.isInCorridor(CanonicalClass.CAR, 2.1f))
        assertTrue(geo.isInCorridor(CanonicalClass.PEDESTRIAN, 2.4f))
        assertFalse(geo.isInCorridor(CanonicalClass.PEDESTRIAN, 2.6f))
    }

    @Test
    fun fusionPrefersIpmButTakesMinOnDisagreement() {
        val est = GroundGeometry.Estimate()

        // agreeing: box bottom at v(20m), width consistent with ~20 m car
        val v20 = geo.vForDistance(20f)
        val wNormAgree = intrinsics.fx * CanonicalClass.CAR.widthMeters / 20f / intrinsics.width
        geo.estimate(CanonicalClass.CAR, v20, wNormAgree, est)
        assertEquals(GroundGeometry.Estimate.Method.IPM, est.method)
        assertEquals(20f, est.zMeters, 0.5f)
        assertFalse(est.lowConfidence)

        // disagreeing >35%: width says 10 m, IPM says 20 m -> min + low confidence
        val wNorm10 = intrinsics.fx * CanonicalClass.CAR.widthMeters / 10f / intrinsics.width
        geo.estimate(CanonicalClass.CAR, v20, wNorm10, est)
        assertEquals(GroundGeometry.Estimate.Method.FUSED_MIN, est.method)
        assertEquals(10f, est.zMeters, 0.5f)
        assertTrue(est.lowConfidence)

        // cut-off box (bottom at frame edge) -> width prior
        geo.estimate(CanonicalClass.CAR, 0.995f, wNorm10, est)
        assertEquals(GroundGeometry.Estimate.Method.WIDTH_PRIOR, est.method)
    }

    @Test
    fun fallbackIntrinsicsMatch66DegFov() {
        val intr = CameraIntrinsics.fallback(640, 480)
        // fx = (640/2)/tan(33 deg) = 320/0.6494 = 492.7
        assertEquals(492.7f, intr.fx, 1f)
    }
}

package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.alerts.ActiveAlert
import com.deepmost.rabbitav.core.alerts.AdasAlertEngine
import com.deepmost.rabbitav.core.alerts.AlertKind
import com.deepmost.rabbitav.core.alerts.AlertLevel
import com.deepmost.rabbitav.core.alerts.AlertTuning
import com.deepmost.rabbitav.core.alerts.HysteresisGate
import com.deepmost.rabbitav.core.ego.EgoState
import com.deepmost.rabbitav.core.ego.GpsTrail
import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.core.inference.DetectionBuffer
import com.deepmost.rabbitav.core.inference.LetterboxMeta
import com.deepmost.rabbitav.core.inference.ModelConfig
import com.deepmost.rabbitav.core.inference.decode.Nms
import com.deepmost.rabbitav.core.inference.decode.OutputTensorInfo
import com.deepmost.rabbitav.core.inference.decode.YoloV8Decoder
import com.deepmost.rabbitav.core.tracking.TrackSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.DataType

class NmsTest {
    @Test
    fun overlappingSuppressedDisjointKept() {
        val nms = Nms(8)
        val cx = floatArrayOf(0.5f, 0.51f, 0.9f)
        val cy = floatArrayOf(0.5f, 0.5f, 0.9f)
        val w = floatArrayOf(0.2f, 0.2f, 0.1f)
        val h = floatArrayOf(0.2f, 0.2f, 0.1f)
        val score = floatArrayOf(0.9f, 0.8f, 0.7f)
        val keep = BooleanArray(3)
        val kept = nms.run(cx, cy, w, h, score, 3, 0.5f, keep)
        assertEquals(2, kept)
        assertTrue(keep[0])
        assertFalse(keep[1]) // suppressed by higher-scoring twin
        assertTrue(keep[2])
    }
}

class LetterboxMetaTest {
    @Test
    fun vga_to_320_square() {
        val meta = LetterboxMeta()
        meta.configure(640, 480, 320, 320)
        assertEquals(320, meta.contentW)
        assertEquals(240, meta.contentH)
        assertEquals(0, meta.padX)
        assertEquals(40, meta.padY)
        // model-space center maps to frame center
        assertEquals(0.5f, meta.unmapX(160f), 1e-3f)
        assertEquals(0.5f, meta.unmapY(160f), 1e-3f)
        // model y at pad boundary maps to 0
        assertEquals(0f, meta.unmapY(40f), 1e-3f)
    }
}

class YoloDecoderTest {

    private val config = ModelConfig(
        schema = 1,
        name = "test",
        input = ModelConfig.InputSpec(320, 320, quantized = false, resizable = true),
        decode = ModelConfig.DecodeSpec(family = "yolo_v8", confThreshold = 0.35f, iouThreshold = 0.5f),
        classes = listOf("person", "bicycle", "car"),
        classMap = mapOf("person" to "PEDESTRIAN", "car" to "CAR"), // bicycle deliberately unmapped
    )

    /** Builds a transposed [1, 4+nc, N] float tensor. */
    private fun tensor(candidates: List<FloatArray>): OutputTensorInfo {
        val nc = config.classes.size
        val attrs = 4 + nc
        val n = candidates.size
        val buf = ByteBuffer.allocateDirect(attrs * n * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (a in 0 until attrs) {
            for (j in 0 until n) {
                fb.put(a * n + j, candidates[j][a])
            }
        }
        return OutputTensorInfo(0, intArrayOf(1, attrs, n), DataType.FLOAT32, 1f, 0, buf)
    }

    @Test
    fun decodesMapsAndDropsUnmapped() {
        val meta = LetterboxMeta().apply { configure(640, 480, 320, 320) }
        val decoder = YoloV8Decoder(config.classes.size, 8)
        // candidate boxes in model pixels (cx, cy, w, h, p_person, p_bicycle, p_car)
        val outputs = listOf(
            tensor(
                listOf(
                    floatArrayOf(160f, 160f, 60f, 40f, 0.05f, 0.02f, 0.9f),  // car at center
                    floatArrayOf(80f, 200f, 30f, 60f, 0.8f, 0.05f, 0.02f),   // person left
                    floatArrayOf(240f, 200f, 30f, 60f, 0.05f, 0.9f, 0.02f),  // bicycle -> unmapped, dropped
                    floatArrayOf(300f, 100f, 20f, 20f, 0.1f, 0.1f, 0.1f),    // below threshold
                )
            )
        )
        val out = DetectionBuffer()
        decoder.decode(outputs, meta, config, out)

        assertEquals(2, out.size)
        val car = (0 until out.size).map { out.items[it] }.first { it.canonical == CanonicalClass.CAR }
        assertEquals(0.9f, car.score, 1e-3f)
        assertEquals(0.5f, car.cx, 1e-3f) // center of frame
        assertEquals(0.5f, car.cy, 1e-3f)
        assertEquals(60f / 320f, car.w, 1e-3f)
        val person = (0 until out.size).map { out.items[it] }.first { it.canonical == CanonicalClass.PEDESTRIAN }
        assertEquals(80f / 320f, person.cx, 1e-3f)
    }
}

class HysteresisGateTest {
    private val s = 1_000_000_000L

    @Test
    fun holdToFireAndClear() {
        val gate = HysteresisGate(holdToFireNs = (0.4 * s).toLong(), holdToClearNs = s, cooldownNs = 4 * s)
        assertEquals(AlertLevel.NONE, gate.update(AlertLevel.CAUTION, 0))
        assertEquals(AlertLevel.NONE, gate.update(AlertLevel.CAUTION, (0.3 * s).toLong()))
        assertEquals(AlertLevel.CAUTION, gate.update(AlertLevel.CAUTION, (0.45 * s).toLong()))
        // condition drops; must persist 1 s before downgrade
        assertEquals(AlertLevel.CAUTION, gate.update(AlertLevel.NONE, (1.0 * s).toLong()))
        assertEquals(AlertLevel.CAUTION, gate.update(AlertLevel.NONE, (1.5 * s).toLong()))
        assertEquals(AlertLevel.NONE, gate.update(AlertLevel.NONE, (2.1 * s).toLong()))
    }

    @Test
    fun cooldownBlocksSameLevelButNotEscalation() {
        val gate = HysteresisGate((0.4 * s).toLong(), s, 4 * s)
        gate.update(AlertLevel.CAUTION, 0)
        gate.update(AlertLevel.CAUTION, (0.5 * s).toLong())
        assertEquals(AlertLevel.CAUTION, gate.current)
        // clear it
        gate.update(AlertLevel.NONE, (1.0 * s).toLong())
        gate.update(AlertLevel.NONE, (2.1 * s).toLong())
        assertEquals(AlertLevel.NONE, gate.current)
        // immediate re-fire of CAUTION is blocked by the 4 s cooldown
        gate.update(AlertLevel.CAUTION, (2.2 * s).toLong())
        assertEquals(AlertLevel.NONE, gate.update(AlertLevel.CAUTION, (2.8 * s).toLong()))
        // but CRITICAL escalation passes
        gate.update(AlertLevel.CRITICAL, (3.0 * s).toLong())
        assertEquals(AlertLevel.CRITICAL, gate.update(AlertLevel.CRITICAL, (3.5 * s).toLong()))
    }
}

/** M2 gate behavior: FCW CAUTION then CRITICAL fire deterministically. */
class AdasAlertEngineTest {

    private fun snapshot(
        ttc: Float, z: Float, closing: Float = 6f, inCorridor: Boolean = true,
        canonical: CanonicalClass = CanonicalClass.CAR, headway: Float = Float.POSITIVE_INFINITY,
        corridorFor: Float = 2f,
    ) = TrackSnapshot(
        id = 1, canonical = canonical, cx = 0.5f, cy = 0.6f, w = 0.2f, h = 0.15f,
        confirmed = true, hits = 10, score = 0.9f, zMeters = z, closingMps = closing,
        ttcS = ttc, lateralXM = 0f, inCorridor = inCorridor, corridorForS = corridorFor,
        distanceLowConfidence = false, headwayS = headway, coastingForS = 0f,
    )

    private val ego = EgoState(
        timeMs = 1, lat = 12.97, lon = 77.59, speedMps = 50f / 3.6f,
        speedValid = true, headingDeg = 90f, headingValid = true,
    )

    @Test
    fun fcwEscalatesCautionThenCritical() {
        val engine = AdasAlertEngine { AlertTuning() }
        val out = mutableListOf<ActiveAlert>()
        val s = 1_000_000_000L
        var t = 0L

        // TTC 2.0 (caution band) sustained
        while (t < s) {
            engine.evaluate(listOf(snapshot(ttc = 2.0f, z = 20f)), ego, true, t, out)
            t += 40_000_000L
        }
        assertTrue(out.any { it.kind == AlertKind.FCW && it.level == AlertLevel.CAUTION })

        // TTC collapses to 1.2 (critical band) sustained
        val tEnd = t + s
        while (t < tEnd) {
            engine.evaluate(listOf(snapshot(ttc = 1.2f, z = 8f)), ego, true, t, out)
            t += 40_000_000L
        }
        assertTrue(out.any { it.kind == AlertKind.FCW && it.level == AlertLevel.CRITICAL })
    }

    @Test
    fun lowSpeedSuppressesEverything() {
        val engine = AdasAlertEngine { AlertTuning() }
        val out = mutableListOf<ActiveAlert>()
        val slowEgo = ego.copy(speedMps = 5f / 3.6f)
        var t = 0L
        repeat(50) {
            engine.evaluate(listOf(snapshot(ttc = 1.2f, z = 8f)), slowEgo, true, t, out)
            t += 40_000_000L
        }
        assertTrue(out.isEmpty())
    }

    @Test
    fun missingCalibrationSuppressesEverything() {
        val engine = AdasAlertEngine { AlertTuning() }
        val out = mutableListOf<ActiveAlert>()
        var t = 0L
        repeat(50) {
            engine.evaluate(listOf(snapshot(ttc = 1.2f, z = 8f)), ego, false, t, out)
            t += 40_000_000L
        }
        assertTrue(out.isEmpty())
    }

    @Test
    fun headwayAdvisoryAndWarning() {
        val engine = AdasAlertEngine { AlertTuning() }
        val out = mutableListOf<ActiveAlert>()
        var t = 0L
        // headway 0.8 s (advisory band), lead stable, not closing (no FCW)
        repeat(30) {
            engine.evaluate(
                listOf(snapshot(ttc = Float.POSITIVE_INFINITY, z = 12f, closing = 0f, headway = 0.8f)),
                ego, true, t, out
            )
            t += 40_000_000L
        }
        assertTrue(out.any { it.kind == AlertKind.HEADWAY && it.level == AlertLevel.ADVISORY })
        out.clear()
        repeat(40) {
            engine.evaluate(
                listOf(snapshot(ttc = Float.POSITIVE_INFINITY, z = 6f, closing = 0f, headway = 0.5f)),
                ego, true, t, out
            )
            t += 40_000_000L
        }
        assertTrue(out.any { it.kind == AlertKind.HEADWAY && it.level == AlertLevel.WARNING })
    }

    @Test
    fun vruProximityFires() {
        val engine = AdasAlertEngine { AlertTuning() }
        val out = mutableListOf<ActiveAlert>()
        var t = 0L
        repeat(30) {
            engine.evaluate(
                listOf(
                    snapshot(
                        ttc = Float.POSITIVE_INFINITY, z = 6f, closing = 0f,
                        canonical = CanonicalClass.PEDESTRIAN,
                    )
                ),
                ego, true, t, out
            )
            t += 40_000_000L
        }
        assertTrue(out.any { it.kind == AlertKind.VRU })
    }
}

class GpsTrailTest {

    @Test
    fun positionBehindWalksTheTrail() {
        val trail = GpsTrail()
        // northbound at ~10 m/s: 1 s fixes, 0.00009 deg lat ~ 10 m
        var lat = 12.97000
        for (i in 0 until 10) {
            trail.push(i * 1000L, lat, 77.59, 10f, 0f)
            lat += 0.00009
        }
        val newest = trail.latest()!!
        val behind = trail.positionBehind(25.0)!!
        val d = GpsTrail.haversineMeters(newest.lat, newest.lon, behind.first, behind.second)
        assertEquals(25.0, d, 2.0)
        assertTrue("behind must be south", behind.first < newest.lat)
    }

    @Test
    fun bearingDeltaWrapsAround() {
        assertEquals(20f, GpsTrail.bearingDeltaDeg(350f, 10f), 1e-3f)
        assertEquals(180f, GpsTrail.bearingDeltaDeg(0f, 180f), 1e-3f)
        assertEquals(0f, GpsTrail.bearingDeltaDeg(45f, 45f), 1e-3f)
    }

    @Test
    fun haversineSanity() {
        // 0.001 deg latitude ~ 111.3 m
        assertEquals(111.3, GpsTrail.haversineMeters(12.0, 77.0, 12.001, 77.0), 1.0)
    }
}

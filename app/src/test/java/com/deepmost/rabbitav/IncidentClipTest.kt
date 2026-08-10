package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.camera.ChunkRing
import com.deepmost.rabbitav.core.geometry.PitchMath
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Incident recorder ring logic (Section 5.11): windowing + keyframe alignment. */
class ChunkRingTest {

    private fun chunk(ptsUs: Long, key: Boolean, size: Int = 100) =
        ChunkRing.Chunk(ByteArray(size), ptsUs, key)

    /** 10 fps, keyframe every second, like the real encoder config. */
    private fun fill(ring: ChunkRing, seconds: Int) {
        for (i in 0 until seconds * 10) {
            ring.append(chunk(i * 100_000L, key = i % 10 == 0))
        }
    }

    @Test
    fun keepsRoughlyTheWindow() {
        val ring = ChunkRing(10_000_000L) // 10 s
        fill(ring, 30)
        val snap = ring.snapshot()
        val span = snap.last().ptsUs - snap.first().ptsUs
        assertTrue("span ${span / 1e6}s", span in 9_000_000L..12_000_000L)
    }

    @Test
    fun snapshotStartsOnKeyframe() {
        val ring = ChunkRing(10_000_000L)
        fill(ring, 25)
        assertTrue(ring.snapshot().first().isKeyframe)
    }

    @Test
    fun neverTrimsBelowOneGop() {
        val ring = ChunkRing(2_000_000L)
        // only 1.5 s of data: nothing should be trimmed away to emptiness
        for (i in 0 until 15) ring.append(chunk(i * 100_000L, key = i == 0))
        assertEquals(15, ring.snapshot().size)
    }

    @Test
    fun byteAccountingMatches() {
        val ring = ChunkRing(5_000_000L)
        fill(ring, 20)
        assertEquals(ring.snapshot().sumOf { it.data.size }.toLong(), ring.totalBytes)
    }
}

/** Shared pitch math (calibration wizard + drive-start drift check). */
class PitchMathTest {

    @Test
    fun levelMountIsZero() {
        // gravity along +Y (portrait upright): az = 0 -> pitch 0
        assertEquals(0f, PitchMath.pitchFromGravity(0f, 9.81f, 0f), 1e-3f)
    }

    @Test
    fun knownTiltRecovered() {
        // pitched down 10 deg: gravity acquires a -Z component of g*sin(10 deg)
        val g = 9.81f
        val rad = Math.toRadians(10.0)
        val az = (-g * Math.sin(rad)).toFloat()
        val ay = (g * Math.cos(rad)).toFloat()
        val pitch = PitchMath.pitchFromGravity(0f, ay, az)
        assertEquals(rad, pitch.toDouble(), 1e-3)
    }

    @Test
    fun implausibleVectorRejected() {
        assertTrue(PitchMath.pitchFromGravity(0f, 1f, 0f).isNaN()) // free-fall-ish
        assertTrue(PitchMath.pitchFromGravity(0f, 30f, 0f).isNaN()) // shaking
    }

    @Test
    fun signConvention() {
        // looking DOWN -> negative az -> positive pitch
        assertTrue(PitchMath.pitchFromGravity(0f, 9f, -2f) > 0f)
        assertTrue(PitchMath.pitchFromGravity(0f, 9f, 2f) < 0f)
        assertTrue(abs(PitchMath.pitchFromGravity(0f, 9f, -2f)) > 0.1f)
    }
}

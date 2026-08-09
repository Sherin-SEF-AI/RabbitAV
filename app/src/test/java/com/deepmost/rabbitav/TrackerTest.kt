package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.core.inference.DetectionFrame
import com.deepmost.rabbitav.core.tracking.MultiObjectTracker
import com.deepmost.rabbitav.core.tracking.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 gate: tracker association. */
class TrackerTest {

    private fun frame(tNs: Long, vararg boxes: FloatArray): DetectionFrame {
        val f = DetectionFrame()
        f.timestampNs = tNs
        f.detections.clear()
        for (b in boxes) {
            val d = f.detections.claim()!!
            d.canonical = CanonicalClass.entries[b[4].toInt()]
            d.score = 0.8f
            d.cx = b[0]; d.cy = b[1]; d.w = b[2]; d.h = b[3]
        }
        return f
    }

    private val car = CanonicalClass.CAR.ordinal.toFloat()
    private val ped = CanonicalClass.PEDESTRIAN.ordinal.toFloat()

    @Test
    fun stableIdAcrossFrames() {
        val tracker = MultiObjectTracker()
        var t = 0L
        tracker.update(frame(t, floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, car)))
        val id = tracker.live().single().id
        repeat(5) {
            t += 125_000_000L
            tracker.update(frame(t, floatArrayOf(0.5f + 0.005f * it, 0.5f, 0.2f, 0.2f, car)))
        }
        assertEquals(1, tracker.live().size)
        assertEquals(id, tracker.live().single().id)
        assertEquals(Track.Status.CONFIRMED, tracker.live().single().status)
    }

    @Test
    fun confirmationNeedsThreeHits() {
        val tracker = MultiObjectTracker()
        tracker.update(frame(0, floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, car)))
        assertEquals(Track.Status.TENTATIVE, tracker.live().single().status)
        tracker.update(frame(125_000_000L, floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, car)))
        assertEquals(Track.Status.TENTATIVE, tracker.live().single().status)
        tracker.update(frame(250_000_000L, floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, car)))
        assertEquals(Track.Status.CONFIRMED, tracker.live().single().status)
    }

    @Test
    fun farDetectionSpawnsNewTrack() {
        val tracker = MultiObjectTracker()
        tracker.update(frame(0, floatArrayOf(0.3f, 0.5f, 0.15f, 0.15f, car)))
        tracker.update(
            frame(
                125_000_000L,
                floatArrayOf(0.3f, 0.5f, 0.15f, 0.15f, car),
                floatArrayOf(0.8f, 0.5f, 0.15f, 0.15f, car),
            )
        )
        assertEquals(2, tracker.live().size)
    }

    @Test
    fun classGroupsDoNotCrossAssociate() {
        val tracker = MultiObjectTracker()
        tracker.update(frame(0, floatArrayOf(0.5f, 0.5f, 0.2f, 0.3f, ped)))
        // a car appearing exactly on top of the pedestrian must NOT steal its track
        tracker.update(frame(125_000_000L, floatArrayOf(0.5f, 0.5f, 0.2f, 0.3f, car)))
        assertEquals(2, tracker.live().size)
    }

    @Test
    fun coastingTrackDiesAfter700ms() {
        val tracker = MultiObjectTracker()
        tracker.update(frame(0, floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, car)))
        assertEquals(1, tracker.live().size)
        // frames with no detections
        tracker.update(frame(400_000_000L))
        assertEquals(1, tracker.live().size) // still coasting
        tracker.update(frame(800_000_000L))
        assertTrue(tracker.live().isEmpty())
    }
}

package com.deepmost.rabbitav.core.tracking

import com.deepmost.rabbitav.core.geometry.GroundGeometry
import com.deepmost.rabbitav.core.inference.DetectionFrame

/**
 * Thread-safe facade over the tracker: the inference thread pushes detector
 * updates; the 25 Hz alert loop pulls annotated snapshots. This 25 Hz
 * prediction between 8 Hz detector updates is the core trick that makes
 * budget-phone ADAS feel real: distance, TTC, and alerts move smoothly even
 * when the detector only lands a few frames per second.
 */
class TrackerHub {

    private val tracker = MultiObjectTracker()
    private val lock = Any()
    private val estimateScratch = GroundGeometry.Estimate()

    /** Inference thread: associate + correct with fresh detections. */
    fun update(frame: DetectionFrame) {
        synchronized(lock) {
            tracker.update(frame)
        }
    }

    fun clear() {
        synchronized(lock) { tracker.clear() }
    }

    /**
     * Alert loop: predict all tracks to [tNs], annotate distance/TTC through
     * [geometry], and return immutable snapshots. [egoSpeedMps] may be NaN
     * (unknown); headway stays infinite then.
     */
    fun predictAndSnapshot(
        tNs: Long,
        geometry: GroundGeometry?,
        egoSpeedMps: Float,
    ): List<TrackSnapshot> {
        synchronized(lock) {
            val live = tracker.live()
            if (live.isEmpty()) return emptyList()
            val out = ArrayList<TrackSnapshot>(live.size)
            for (t in live) {
                t.predictTo(tNs)

                if (geometry != null && geometry.calibration.valid) {
                    annotate(t, geometry, egoSpeedMps, tNs)
                } else {
                    t.zMeters = Float.NaN
                    t.closingMps = 0f
                    t.ttcS = Float.POSITIVE_INFINITY
                    t.inCorridor = false
                }

                out.add(
                    TrackSnapshot(
                        id = t.id,
                        canonical = t.canonical,
                        cx = t.cx, cy = t.cy, w = t.w, h = t.h,
                        confirmed = t.status == Track.Status.CONFIRMED,
                        hits = t.hits,
                        score = t.score,
                        zMeters = t.zMeters,
                        closingMps = t.closingMps,
                        ttcS = t.ttcS,
                        lateralXM = t.lateralXM,
                        inCorridor = t.inCorridor,
                        corridorForS = if (t.corridorSinceNs == 0L) 0f else (tNs - t.corridorSinceNs) / 1e9f,
                        distanceLowConfidence = t.distanceLowConfidence,
                        headwayS = t.headwayS,
                        coastingForS = (tNs - t.lastMatchNs).coerceAtLeast(0L) / 1e9f,
                    )
                )
            }
            return out
        }
    }

    private fun annotate(t: Track, geometry: GroundGeometry, egoSpeedMps: Float, tNs: Long) {
        val est = geometry.estimate(t.canonical, t.bottom, t.w, estimateScratch)
        if (!est.zMeters.isNaN()) {
            // Measurement variance grows with distance (bbox quantization) and
            // with low-confidence fusion: sigma ~ 8% of Z, doubled when fused.
            val sigma = est.zMeters * (if (est.lowConfidence) 0.16f else 0.08f)
            t.fZ.update(est.zMeters, sigma * sigma)
        }
        t.distanceLowConfidence = est.lowConfidence
        if (t.fZ.isInitialized) {
            t.zMeters = t.fZ.p
            t.closingMps = -t.fZ.v
            // TTC defined only when genuinely closing (> 0.8 m/s, Section 5.4).
            t.ttcS = if (t.closingMps > MIN_CLOSING_FOR_TTC_MPS) {
                (t.zMeters / t.closingMps).coerceAtLeast(0f)
            } else {
                Float.POSITIVE_INFINITY
            }
        }

        if (!t.zMeters.isNaN()) {
            t.lateralXM = geometry.lateralOffset(t.zMeters, t.cx)
            val inC = geometry.isInCorridor(t.canonical, t.lateralXM)
            if (inC && !t.inCorridor) t.corridorSinceNs = tNs
            if (!inC) t.corridorSinceNs = 0L
            t.inCorridor = inC
            t.headwayS = if (inC && egoSpeedMps > 1f && !t.zMeters.isNaN()) {
                t.zMeters / egoSpeedMps
            } else {
                Float.POSITIVE_INFINITY
            }
        } else {
            t.lateralXM = Float.NaN
            t.inCorridor = false
            t.corridorSinceNs = 0L
            t.headwayS = Float.POSITIVE_INFINITY
        }
    }

    companion object {
        /** Closing speeds below this leave TTC undefined (Section 5.4). */
        const val MIN_CLOSING_FOR_TTC_MPS = 0.8f
    }
}

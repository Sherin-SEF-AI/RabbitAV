package com.deepmost.rabbitav.core.tracking

import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.core.inference.Detection

/**
 * One tracked object. Box state is four independent PvKalman filters over
 * normalized [cx, cy, w, h]; distance state is a fifth over [Z, dZ/dt] fed at
 * 25 Hz by the alert loop (Section 5.4).
 */
class Track(
    val id: Int,
    firstDetection: Detection,
    val bornNs: Long,
) {
    enum class Status { TENTATIVE, CONFIRMED }

    // --- box filters (normalized image units) ---
    // processNoise 0.35: a box can accelerate ~0.6 frame-widths/s^2 before the
    // filter lags visibly. measurementNoise 0.004 ~= (2% of frame)^2 detector jitter.
    val fCx = PvKalman(0.35f, 0.004f)
    val fCy = PvKalman(0.35f, 0.004f)
    val fW = PvKalman(0.15f, 0.004f)
    val fH = PvKalman(0.15f, 0.004f)

    // --- distance filter (meters) ---
    // processNoise 6: relative longitudinal accel up to ~2.5 m/s^2 tracked
    // within ~0.5 s. Measurement noise supplied per-update (scales with Z).
    val fZ = PvKalman(6f, 4f)

    var status = Status.TENTATIVE
    var hits = 1
    var lastMatchNs = bornNs
    var lastPredictNs = bornNs
    var score = firstDetection.score

    /** Majority-vote class across the track's life (survives car<->truck flicker). */
    private val classVotes = FloatArray(CanonicalClass.entries.size)
    var canonical: CanonicalClass = firstDetection.canonical
        private set

    // --- geometry annotations, written by the alert loop each tick ---
    var zMeters = Float.NaN
    var closingMps = 0f
    var ttcS = Float.POSITIVE_INFINITY
    var lateralXM = Float.NaN
    var inCorridor = false
    var distanceLowConfidence = false
    var headwayS = Float.POSITIVE_INFINITY
    /** Time this track has continuously been in the ego corridor, seconds. */
    var corridorSinceNs = 0L

    init {
        fCx.reset(firstDetection.cx)
        fCy.reset(firstDetection.cy)
        fW.reset(firstDetection.w, posVar = 0.01f)
        fH.reset(firstDetection.h, posVar = 0.01f)
        classVotes[firstDetection.canonical.ordinal] = firstDetection.score
    }

    val cx: Float get() = fCx.p
    val cy: Float get() = fCy.p
    val w: Float get() = maxOf(fW.p, 1e-3f)
    val h: Float get() = maxOf(fH.p, 1e-3f)
    val bottom: Float get() = cy + h / 2f

    fun predictTo(tNs: Long) {
        val dt = (tNs - lastPredictNs) / 1e9f
        if (dt <= 0f) return
        // clamp dt: a stalled pipeline must not fling boxes across the frame
        val d = dt.coerceAtMost(0.5f)
        fCx.predict(d)
        fCy.predict(d)
        fW.predict(d)
        fH.predict(d)
        fZ.predict(d)
        lastPredictNs = tNs
    }

    fun correct(det: Detection, tNs: Long) {
        fCx.update(det.cx)
        fCy.update(det.cy)
        fW.update(det.w)
        fH.update(det.h)
        hits++
        lastMatchNs = tNs
        score = 0.7f * score + 0.3f * det.score
        classVotes[det.canonical.ordinal] += det.score
        var best = 0
        for (i in classVotes.indices) if (classVotes[i] > classVotes[best]) best = i
        canonical = CanonicalClass.entries[best]
        if (status == Status.TENTATIVE && hits >= CONFIRM_HITS) status = Status.CONFIRMED
    }

    fun iouWith(det: Detection): Float {
        val l1 = cx - w / 2f
        val t1 = cy - h / 2f
        val r1 = cx + w / 2f
        val b1 = cy + h / 2f
        val l = maxOf(l1, det.left)
        val t = maxOf(t1, det.top)
        val r = minOf(r1, det.right)
        val b = minOf(b1, det.bottom)
        if (r <= l || b <= t) return 0f
        val inter = (r - l) * (b - t)
        val union = w * h + det.w * det.h - inter
        return if (union > 0f) inter / union else 0f
    }

    companion object {
        /** Hits to graduate TENTATIVE -> CONFIRMED (Section 5.3). */
        const val CONFIRM_HITS = 3

        /** Track dies after this long without a match, coasting meanwhile. */
        const val MAX_COAST_NS = 700_000_000L
    }
}

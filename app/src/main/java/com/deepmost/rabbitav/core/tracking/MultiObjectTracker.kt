package com.deepmost.rabbitav.core.tracking

import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.core.inference.DetectionFrame
import timber.log.Timber

/**
 * IOU-gated greedy association + per-track Kalman (Section 5.3). update() runs
 * on the inference thread at detector rate; the 25 Hz alert loop calls
 * predict/annotate through [TrackerHub]'s lock.
 */
class MultiObjectTracker {

    private val tracks = ArrayList<Track>(MAX_TRACKS)
    private var nextId = 1

    /** Association candidates scratch (avoids per-update allocation). */
    private val candTrack = IntArray(MAX_TRACKS * 8)
    private val candDet = IntArray(MAX_TRACKS * 8)
    private val candIou = FloatArray(MAX_TRACKS * 8)
    private val detTaken = BooleanArray(64)
    private val trackTaken = BooleanArray(MAX_TRACKS)

    fun update(frame: DetectionFrame) {
        val tNs = frame.timestampNs
        val dets = frame.detections

        // Predict all tracks to the frame's capture time before matching.
        for (t in tracks) t.predictTo(tNs)

        // Build gated candidate list.
        var nCand = 0
        for (ti in tracks.indices) {
            val track = tracks[ti]
            for (di in 0 until dets.size) {
                val det = dets.items[di]
                if (!compatible(track.canonical, det.canonical)) continue
                val iou = track.iouWith(det)
                if (iou >= IOU_GATE && nCand < candIou.size) {
                    candTrack[nCand] = ti
                    candDet[nCand] = di
                    candIou[nCand] = iou
                    nCand++
                }
            }
        }

        // Greedy: repeatedly take the best remaining pair.
        java.util.Arrays.fill(detTaken, 0, dets.size, false)
        java.util.Arrays.fill(trackTaken, 0, tracks.size, false)
        var remaining = nCand
        while (remaining > 0) {
            var bestIdx = -1
            var bestIou = 0f
            for (i in 0 until nCand) {
                if (candIou[i] > bestIou && !trackTaken[candTrack[i]] && !detTaken[candDet[i]]) {
                    bestIou = candIou[i]
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            val ti = candTrack[bestIdx]
            val di = candDet[bestIdx]
            trackTaken[ti] = true
            detTaken[di] = true
            tracks[ti].correct(dets.items[di], tNs)
            remaining--
        }

        // Unmatched detections spawn tentative tracks (capacity permitting).
        for (di in 0 until dets.size) {
            if (detTaken[di]) continue
            if (tracks.size >= MAX_TRACKS) break
            tracks.add(Track(nextId++, dets.items[di], tNs))
        }

        // Reap tracks that have coasted too long.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (tNs - t.lastMatchNs > Track.MAX_COAST_NS) it.remove()
        }
        if (tracks.size == MAX_TRACKS) {
            Timber.tag(TAG).w("track capacity %d reached", MAX_TRACKS)
        }
    }

    /** Live tracks; caller must hold TrackerHub's lock. */
    fun live(): List<Track> = tracks

    fun clear() {
        tracks.clear()
    }

    /** Association groups: vehicles with vehicles, VRU/animal with VRU/animal.
     *  Cross-group association would let a passing bus eat a pedestrian track. */
    private fun compatible(a: CanonicalClass, b: CanonicalClass): Boolean {
        if (a == b) return true
        val aVeh = a.isVehicle
        val bVeh = b.isVehicle
        if (aVeh && bVeh) return true
        return a.isVru && b.isVru
    }

    companion object {
        private const val TAG = "RAV-Track"

        /** IOU association gate (Section 5.3). */
        const val IOU_GATE = 0.3f
        const val MAX_TRACKS = 32
    }
}

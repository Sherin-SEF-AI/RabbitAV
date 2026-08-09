package com.deepmost.rabbitav.core.hazard

import com.deepmost.rabbitav.core.camera.LookbackRingBuffer
import com.deepmost.rabbitav.core.ego.EgoEstimator
import com.deepmost.rabbitav.core.imu.HazardCandidate
import com.deepmost.rabbitav.core.imu.HazardType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Section 5.7: IMU candidate + lookback frame + ego trail -> stored hazard.
 *
 * Lookback: the hazard was under the wheels at jolt time but visible
 * dtLookback = clamp(8 m / egoSpeed, 0.3 s, 2.5 s) earlier. Geotag: the point
 * egoSpeed * dtLookback meters BEHIND the current position along the trail.
 */
class HazardFusion(
    private val scope: CoroutineScope,
    private val egoEstimator: EgoEstimator,
    private val visualClassifier: HazardVisualClassifier,
    private val store: HazardStore,
    private val ringProvider: () -> LookbackRingBuffer?,
    private val tripIdProvider: () -> Long,
    private val onStored: (NewHazardEvent, siteId: Long) -> Unit = { _, _ -> },
) {
    fun onCandidate(candidate: HazardCandidate) {
        scope.launch {
            try {
                fuse(candidate)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "hazard fusion failed")
            }
        }
    }

    private suspend fun fuse(c: HazardCandidate) {
        val speed = maxOf(c.egoSpeedMps, 1f)
        val dtLookbackS = (LOOKBACK_DISTANCE_M / speed).coerceIn(MIN_LOOKBACK_S, MAX_LOOKBACK_S)

        val ring = ringProvider()
        val frame = ring?.nearest(c.timestampNs - (dtLookbackS * 1e9f).toLong())
        val visual = if (frame != null) visualClassifier.classify(frame) else null

        // Fusion policy (Section 5.7)
        val type: HazardType
        val confidence: Float
        val source: HazardSource
        when {
            visual == null -> {
                type = c.type
                confidence = c.imuConfidence
                source = HazardSource.IMU
            }
            visual.type == c.type -> {
                type = c.type
                confidence = 0.45f * c.imuConfidence + 0.55f * visual.confidence
                source = HazardSource.FUSED
            }
            else -> {
                type = visual.type
                confidence = 0.6f * visual.confidence
                source = HazardSource.FUSED
            }
        }
        if (type == HazardType.UNKNOWN && confidence < 0.5f) {
            Timber.tag(TAG).d("dropping UNKNOWN low-confidence jolt (conf=%.2f)", confidence)
            return
        }

        val ego = egoEstimator.state.value
        val behindM = (c.egoSpeedMps * dtLookbackS).toDouble()
        val pos = egoEstimator.trail.positionBehind(behindM)
        if (pos == null) {
            Timber.tag(TAG).w("no GPS trail; hazard not geotagged, dropped")
            return
        }

        val event = NewHazardEvent(
            type = type,
            confidence = confidence.coerceIn(0f, 0.99f),
            lat = pos.first,
            lon = pos.second,
            headingDeg = ego.headingDeg,
            speedMps = c.egoSpeedMps,
            source = source,
            timeMs = System.currentTimeMillis(),
            tripId = tripIdProvider(),
        )
        val siteId = store.recordEvent(event)
        Timber.tag(TAG).i(
            "hazard stored: %s conf=%.2f at (%.6f,%.6f) src=%s site=%d lookback=%.1fs visual=%s",
            type, confidence, pos.first, pos.second, source, siteId, dtLookbackS, visual?.type
        )
        onStored(event, siteId)
    }

    /** Manual report chips (Section 5.7): full events at confidence 0.8,
     *  geotagged egoSpeed * 1.5 s behind the current position. */
    fun onManualReport(type: HazardType) {
        scope.launch {
            try {
                val ego = egoEstimator.state.value
                val behind = (ego.speedMps * MANUAL_LOOKBACK_S).toDouble()
                val pos = egoEstimator.trail.positionBehind(behind) ?: run {
                    Timber.tag(TAG).w("manual report without GPS trail; dropped")
                    return@launch
                }
                val event = NewHazardEvent(
                    type = type,
                    confidence = MANUAL_CONFIDENCE,
                    lat = pos.first,
                    lon = pos.second,
                    headingDeg = ego.headingDeg,
                    speedMps = ego.speedMps,
                    source = HazardSource.MANUAL,
                    timeMs = System.currentTimeMillis(),
                    tripId = tripIdProvider(),
                )
                val siteId = store.recordEvent(event)
                Timber.tag(TAG).i("manual hazard: %s site=%d", type, siteId)
                onStored(event, siteId)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "manual report failed")
            }
        }
    }

    companion object {
        private const val TAG = "RAV-Hazard"

        /** The hazard is assumed visible ~8 m ahead of the wheels (Section 5.7). */
        const val LOOKBACK_DISTANCE_M = 8.0f
        const val MIN_LOOKBACK_S = 0.3f
        const val MAX_LOOKBACK_S = 2.5f

        /** Manual chip events (Section 5.7): conf 0.8, 1.5 s behind. */
        const val MANUAL_CONFIDENCE = 0.8f
        const val MANUAL_LOOKBACK_S = 1.5f
    }
}

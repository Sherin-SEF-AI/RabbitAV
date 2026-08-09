package com.deepmost.rabbitav.core.ego

import android.location.Location
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/** Ego motion state consumed by geometry, alerts, and hazard geotagging. */
data class EgoState(
    val timeMs: Long = 0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val speedMps: Float = 0f,
    /** False when GPS is stale > 3 s — speed-gated alerts are suppressed. */
    val speedValid: Boolean = false,
    val headingDeg: Float = 0f,
    val headingValid: Boolean = false,
    val synthetic: Boolean = false,
) {
    val speedKmh: Float get() = speedMps * 3.6f
}

/**
 * GPS speed (Doppler) low-pass filtered with alpha 0.4 (Section 5.4), heading
 * from GPS course, 10 s trail for hazard geotagging. Replay mode substitutes a
 * synthetic ego speed (debug slider) and dead-reckons a position so the whole
 * hazard pipeline can be exercised at a desk.
 */
@Singleton
class EgoEstimator @Inject constructor() {

    private val _state = MutableStateFlow(EgoState())
    val state: StateFlow<EgoState> = _state

    val trail = GpsTrail()

    private var filteredSpeed = 0f
    private var haveSpeed = false
    private var lastFixElapsedMs = 0L

    // --- synthetic (replay) mode ---
    @Volatile private var syntheticMode = false
    @Volatile private var syntheticSpeedMps = 0f
    private var synthLat = 12.9716 // Bengaluru; arbitrary but real-looking on the map
    private var synthLon = 77.5946
    private var synthHeading = 45f
    private var lastSynthTickMs = 0L

    fun setSyntheticMode(enabled: Boolean) {
        syntheticMode = enabled
        if (enabled) {
            lastSynthTickMs = SystemClock.elapsedRealtime()
            Timber.tag(TAG).i("synthetic ego mode ON")
        } else {
            haveSpeed = false
            Timber.tag(TAG).i("synthetic ego mode OFF")
        }
    }

    fun setSyntheticSpeedKmh(kmh: Float) {
        syntheticSpeedMps = (kmh / 3.6f).coerceIn(0f, 60f)
    }

    /** Live GPS input; 1 Hz. */
    fun feedLocation(loc: Location) {
        if (syntheticMode) return
        val now = SystemClock.elapsedRealtime()
        val rawSpeed = if (loc.hasSpeed()) loc.speed else 0f
        filteredSpeed = if (!haveSpeed) rawSpeed else {
            // alpha 0.4: ~1.5 s to converge on a step change at 1 Hz fixes
            SPEED_LP_ALPHA * rawSpeed + (1f - SPEED_LP_ALPHA) * filteredSpeed
        }
        haveSpeed = true
        lastFixElapsedMs = now

        val heading = if (loc.hasBearing()) loc.bearing else _state.value.headingDeg
        val headingValid = loc.hasBearing() || _state.value.headingValid

        trail.push(System.currentTimeMillis(), loc.latitude, loc.longitude, filteredSpeed, heading)
        _state.value = EgoState(
            timeMs = System.currentTimeMillis(),
            lat = loc.latitude,
            lon = loc.longitude,
            speedMps = filteredSpeed,
            speedValid = true,
            headingDeg = heading,
            headingValid = headingValid,
            synthetic = false,
        )
    }

    /**
     * Called by the 25 Hz alert loop: staleness bookkeeping in live mode,
     * dead-reckoning in synthetic mode (position advances along heading so
     * hazard geotagging and approach alerts work in replay).
     */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (syntheticMode) {
            val dtS = if (lastSynthTickMs == 0L) 0f else (now - lastSynthTickMs) / 1000f
            lastSynthTickMs = now
            if (syntheticSpeedMps > 0.1f && dtS > 0f) {
                val (la, lo) = GpsTrail.offset(synthLat, synthLon, synthHeading, (syntheticSpeedMps * dtS).toDouble())
                synthLat = la
                synthLon = lo
            }
            // Trail at ~1 Hz equivalent: push every 25th tick would drift; push
            // when 1 s has passed since last trail entry instead.
            val latest = trail.latest()
            if (latest == null || System.currentTimeMillis() - latest.timeMs >= 1000L) {
                trail.push(System.currentTimeMillis(), synthLat, synthLon, syntheticSpeedMps, synthHeading)
            }
            _state.value = EgoState(
                timeMs = System.currentTimeMillis(),
                lat = synthLat,
                lon = synthLon,
                speedMps = syntheticSpeedMps,
                speedValid = true,
                headingDeg = synthHeading,
                headingValid = true,
                synthetic = true,
            )
            return
        }

        // Live mode: invalidate speed when GPS goes stale (tunnel, poor sky).
        if (haveSpeed && now - lastFixElapsedMs > SPEED_STALE_MS) {
            val s = _state.value
            if (s.speedValid) {
                Timber.tag(TAG).w("GPS stale > %d ms; ego speed unknown", SPEED_STALE_MS)
                _state.value = s.copy(speedValid = false)
            }
        }
    }

    fun reset() {
        haveSpeed = false
        filteredSpeed = 0f
        trail.clear()
        _state.value = EgoState()
    }

    companion object {
        private const val TAG = "RAV-Ego"

        /** Speed LP filter alpha (Section 5.4). */
        const val SPEED_LP_ALPHA = 0.4f

        /** GPS speed staleness horizon (Section 5.4): 3 s. */
        const val SPEED_STALE_MS = 3000L
    }
}

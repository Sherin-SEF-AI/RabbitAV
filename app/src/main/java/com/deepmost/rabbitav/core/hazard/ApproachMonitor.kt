package com.deepmost.rabbitav.core.hazard

import com.deepmost.rabbitav.core.alerts.ActiveAlert
import com.deepmost.rabbitav.core.alerts.AlertKind
import com.deepmost.rabbitav.core.alerts.AlertLevel
import com.deepmost.rabbitav.core.alerts.AlertTuning
import com.deepmost.rabbitav.core.alerts.Tone
import com.deepmost.rabbitav.core.ego.EgoState
import com.deepmost.rabbitav.core.ego.GpsTrail
import com.deepmost.rabbitav.core.imu.HazardType
import kotlin.math.atan2
import kotlin.math.cos
import timber.log.Timber

/**
 * Section 5.5 "hazard ahead (mapped)": warns once per site per pass when the
 * ego crosses the 120 m boundary along its heading. Site queries run at GPS
 * rate (1 Hz) on a background dispatcher; the alert loop merges [currentAlerts]
 * into the arbiter set each tick.
 */
class ApproachMonitor(
    private val store: HazardStore,
    private val tuningProvider: () -> AlertTuning,
    /** Localized TTS line for a hazard type ("speed breaker ahead"). */
    private val speechProvider: (HazardType) -> String,
) {
    private class SiteState {
        var lastDistanceM = Double.MAX_VALUE
        var firedAtMs = 0L
    }

    private val siteStates = HashMap<Long, SiteState>()

    @Volatile private var active: List<ActiveAlert> = emptyList()
    @Volatile var ttsEnabled = true

    /** Called by the alert loop every tick; cheap. Expires stale alerts. */
    fun currentAlerts(nowMs: Long): List<ActiveAlert> {
        val a = active
        if (a.isEmpty()) return a
        val keep = a.filter { nowMs - alertBornMs < ALERT_DISPLAY_MS }
        if (keep.size != a.size) active = keep
        return keep
    }

    @Volatile private var alertBornMs = 0L

    /** Called on each ego update (1 Hz), off the alert thread. */
    suspend fun onEgoUpdate(ego: EgoState) {
        if (!ego.speedValid || !ego.headingValid) return
        val tuning = tuningProvider()
        if (ego.speedKmh < tuning.hazardApproachMinSpeedKmh) return

        val sites = try {
            store.sitesNear(ego.lat, ego.lon, QUERY_RADIUS_M)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "site query failed")
            return
        }
        val nowMs = System.currentTimeMillis()
        val newAlerts = ArrayList<ActiveAlert>(2)

        for (site in sites) {
            val dist = GpsTrail.haversineMeters(ego.lat, ego.lon, site.lat, site.lon)
            val state = siteStates.getOrPut(site.id) { SiteState() }

            // reset the once-per-pass latch when we are far away again
            if (dist > RESET_DISTANCE_M || nowMs - state.firedAtMs > RESET_TIME_MS) {
                if (state.firedAtMs != 0L && dist > RESET_DISTANCE_M) state.firedAtMs = 0L
            }

            val bearingToSite = bearingDeg(ego.lat, ego.lon, site.lat, site.lon)
            val ahead = GpsTrail.bearingDeltaDeg(ego.headingDeg, bearingToSite) < tuning.hazardBearingGateDeg
            val crossed = state.lastDistanceM > tuning.hazardApproachM && dist <= tuning.hazardApproachM
            state.lastDistanceM = dist

            if (crossed && ahead && state.firedAtMs == 0L) {
                state.firedAtMs = nowMs
                alertBornMs = nowMs
                newAlerts.add(
                    ActiveAlert(
                        kind = AlertKind.HAZARD_MAPPED,
                        level = AlertLevel.ADVISORY,
                        tone = Tone.HAZARD_MAPPED,
                        trackId = -site.id.toInt(),
                        distanceM = dist.toFloat(),
                        speech = if (ttsEnabled) speechProvider(site.type) else "",
                        hudTextKey = when (site.type) {
                            HazardType.SPEED_BREAKER -> "alert_breaker_ahead"
                            HazardType.POTHOLE -> "alert_pothole_ahead"
                            HazardType.WATERLOGGING -> "alert_water_ahead"
                            else -> "alert_rough_ahead"
                        },
                    )
                )
                Timber.tag(TAG).i("approach alert: site=%d %s at %.0fm", site.id, site.type, dist)
            }
        }

        if (newAlerts.isNotEmpty()) active = newAlerts

        // GC state for sites far out of range
        if (siteStates.size > 128) {
            val liveIds = sites.mapTo(HashSet()) { it.id }
            siteStates.keys.retainAll { it in liveIds }
        }
    }

    fun reset() {
        siteStates.clear()
        active = emptyList()
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * kotlin.math.sin(Math.toRadians(lat2)) -
            kotlin.math.sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    companion object {
        private const val TAG = "RAV-Hazard"

        /** Sites fetched within this radius each fix; > approach boundary. */
        const val QUERY_RADIUS_M = 250.0

        /** Re-arm distance and time for the once-per-pass latch. */
        const val RESET_DISTANCE_M = 180.0
        const val RESET_TIME_MS = 120_000L

        /** How long a fired approach alert stays on the HUD. */
        const val ALERT_DISPLAY_MS = 4000L
    }
}

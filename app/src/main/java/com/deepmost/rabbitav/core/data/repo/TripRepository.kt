package com.deepmost.rabbitav.core.data.repo

import com.deepmost.rabbitav.core.alerts.ActiveAlert
import com.deepmost.rabbitav.core.alerts.AlertKind
import com.deepmost.rabbitav.core.data.db.AlertEventDao
import com.deepmost.rabbitav.core.data.db.AlertEventEntity
import com.deepmost.rabbitav.core.data.db.HazardEventDao
import com.deepmost.rabbitav.core.data.db.HazardEventEntity
import com.deepmost.rabbitav.core.data.db.TripDao
import com.deepmost.rabbitav.core.data.db.TripEntity
import com.deepmost.rabbitav.core.ego.EgoState
import com.deepmost.rabbitav.core.ego.GpsTrail
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Trip lifecycle + statistics (Section 5.8). Trips auto start/stop with the
 * drive service; distance integrates 1 Hz GPS haversine segments while speed
 * >= 3 km/h (filters standstill drift — see DECISIONS.md).
 */
@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao,
    private val alertDao: AlertEventDao,
    private val hazardEventDao: HazardEventDao,
) {
    @Volatile var currentTripId: Long = 0
        private set

    @Volatile var currentDistanceM: Double = 0.0
        private set

    private var current: TripEntity? = null
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastMoveMs = 0L

    suspend fun startTrip(mode: String): Long {
        // recover from a crash-orphaned open trip
        tripDao.openTrip()?.let { orphan ->
            tripDao.update(orphan.copy(endMs = orphan.startMs + orphan.movingTimeS * 1000))
            Timber.tag(TAG).w("closed orphaned trip %d", orphan.id)
        }
        val trip = TripEntity(startMs = System.currentTimeMillis(), mode = mode)
        val id = tripDao.insert(trip)
        current = trip.copy(id = id)
        currentTripId = id
        lastLat = Double.NaN
        lastLon = Double.NaN
        Timber.tag(TAG).i("trip %d started (%s)", id, mode)
        return id
    }

    suspend fun endTrip() {
        val trip = current ?: return
        tripDao.update(trip.copy(endMs = System.currentTimeMillis()))
        Timber.tag(TAG).i(
            "trip %d ended: %.1f km, %d alerts, %d hazards",
            trip.id, trip.distanceM / 1000.0,
            trip.fcwCount + trip.headwayCount + trip.vruCount, trip.hazardsLogged
        )
        current = null
        currentTripId = 0
    }

    /** 1 Hz ego updates: distance/speed integration. */
    suspend fun onEgoUpdate(ego: EgoState) {
        val trip = current ?: return
        if (!ego.speedValid) return
        var changed = trip
        if (ego.speedMps * 3.6f >= MIN_MOVING_KMH) {
            if (!lastLat.isNaN()) {
                val d = GpsTrail.haversineMeters(lastLat, lastLon, ego.lat, ego.lon)
                if (d < 100) { // 1 Hz sanity gate: >100 m/s is a GPS glitch
                    changed = changed.copy(distanceM = changed.distanceM + d)
                }
            }
            lastLat = ego.lat
            lastLon = ego.lon
            val now = System.currentTimeMillis()
            if (lastMoveMs != 0L && now - lastMoveMs < 3000) {
                changed = changed.copy(movingTimeS = changed.movingTimeS + (now - lastMoveMs) / 1000)
            }
            lastMoveMs = now
        }
        if (ego.speedMps > changed.maxSpeedMps) changed = changed.copy(maxSpeedMps = ego.speedMps)
        if (changed !== trip) {
            current = changed
            currentDistanceM = changed.distanceM
            tripDao.update(changed)
        }
    }

    /** Alert onset hook from the arbiter. */
    suspend fun onAlert(alert: ActiveAlert, ego: EgoState) {
        val trip = current ?: return
        alertDao.insert(
            AlertEventEntity(
                tripId = trip.id,
                timeMs = System.currentTimeMillis(),
                kind = alert.kind.name,
                level = alert.level.name,
                lat = ego.lat,
                lon = ego.lon,
                speedMps = ego.speedMps,
                // NaN/Infinity bind as NULL in SQLite and violate NOT NULL;
                // -1 is the "not applicable" sentinel for both columns.
                ttcS = alert.secondsToEvent.takeIf { it.isFinite() } ?: -1f,
                distanceM = alert.distanceM.takeIf { it.isFinite() } ?: -1f,
            )
        )
        val bumped = when (alert.kind) {
            AlertKind.FCW, AlertKind.WRONG_SIDE -> trip.copy(fcwCount = trip.fcwCount + 1)
            AlertKind.HEADWAY -> trip.copy(headwayCount = trip.headwayCount + 1)
            AlertKind.VRU -> trip.copy(vruCount = trip.vruCount + 1)
            AlertKind.HAZARD_MAPPED, AlertKind.HAZARD_VISUAL ->
                trip.copy(hazardAheadCount = trip.hazardAheadCount + 1)
        }
        current = bumped
        tripDao.update(bumped)
    }

    suspend fun onHazardLogged() {
        val trip = current ?: return
        val bumped = trip.copy(hazardsLogged = trip.hazardsLogged + 1)
        current = bumped
        tripDao.update(bumped)
    }

    fun recentTrips(): Flow<List<TripEntity>> = tripDao.recentTrips()

    suspend fun tripDetail(id: Long): Triple<TripEntity?, List<AlertEventEntity>, List<HazardEventEntity>> =
        Triple(tripDao.byId(id), alertDao.forTrip(id), hazardEventDao.forTrip(id))

    companion object {
        private const val TAG = "RAV-Data"
        const val MIN_MOVING_KMH = 3f
    }
}

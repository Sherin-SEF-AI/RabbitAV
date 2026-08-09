package com.deepmost.rabbitav.core.data.repo

import com.deepmost.rabbitav.core.data.db.HazardEventDao
import com.deepmost.rabbitav.core.data.db.HazardEventEntity
import com.deepmost.rabbitav.core.data.db.HazardSiteDao
import com.deepmost.rabbitav.core.data.db.HazardSiteEntity
import com.deepmost.rabbitav.core.ego.GpsTrail
import com.deepmost.rabbitav.core.hazard.Geohash
import com.deepmost.rabbitav.core.hazard.HazardStore
import com.deepmost.rabbitav.core.hazard.NewHazardEvent
import com.deepmost.rabbitav.core.hazard.StoredSite
import com.deepmost.rabbitav.core.imu.HazardType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * HazardSite store (Section 5.7): geohash-7 indexed clustering (15 m, heading
 * delta < 45 deg, same type), confidence accumulation
 * `1 - (1-a)(1-b)` capped 0.99, 30-day half-life decay applied on read, weekly
 * pruning of sites below 0.15.
 */
@Singleton
class HazardRepository @Inject constructor(
    private val siteDao: HazardSiteDao,
    private val eventDao: HazardEventDao,
) : HazardStore {

    override suspend fun recordEvent(event: NewHazardEvent): Long {
        val siteId = upsertSite(event)
        eventDao.insert(
            HazardEventEntity(
                tripId = event.tripId,
                timeMs = event.timeMs,
                type = event.type.name,
                confidence = event.confidence,
                lat = event.lat,
                lon = event.lon,
                headingDeg = event.headingDeg,
                speedMps = event.speedMps,
                source = event.source.name,
                siteId = siteId,
            )
        )
        return siteId
    }

    private suspend fun upsertSite(event: NewHazardEvent): Long {
        val hash = Geohash.encode(event.lat, event.lon, 7)
        val candidates = siteDao.byGeohashes(Geohash.withNeighbors(hash))
        val now = event.timeMs

        val match = candidates.firstOrNull { site ->
            site.type == event.type.name &&
                GpsTrail.haversineMeters(site.lat, site.lon, event.lat, event.lon) <= CLUSTER_RADIUS_M &&
                GpsTrail.bearingDeltaDeg(site.headingDeg, event.headingDeg) < CLUSTER_HEADING_DEG
        }

        return if (match == null) {
            siteDao.insert(
                HazardSiteEntity(
                    geohash7 = hash,
                    type = event.type.name,
                    lat = event.lat,
                    lon = event.lon,
                    headingDeg = event.headingDeg,
                    confidence = event.confidence.coerceAtMost(MAX_CONFIDENCE),
                    hitCount = 1,
                    firstSeenMs = now,
                    lastSeenMs = now,
                    avgSpeedMps = event.speedMps,
                )
            )
        } else {
            val decayed = decayedConfidence(match.confidence, match.lastSeenMs, now)
            val merged = 1f - (1f - decayed) * (1f - event.confidence)
            // position eases 25% toward each new observation (keeps drifting
            // GPS from smearing the site while still converging)
            val newLat = match.lat + (event.lat - match.lat) * POSITION_EASE
            val newLon = match.lon + (event.lon - match.lon) * POSITION_EASE
            val n = match.hitCount + 1
            siteDao.update(
                match.copy(
                    geohash7 = Geohash.encode(newLat, newLon, 7),
                    lat = newLat,
                    lon = newLon,
                    headingDeg = circularMix(match.headingDeg, event.headingDeg, 1f / n),
                    confidence = merged.coerceAtMost(MAX_CONFIDENCE),
                    hitCount = n,
                    lastSeenMs = now,
                    avgSpeedMps = match.avgSpeedMps + (event.speedMps - match.avgSpeedMps) / n,
                )
            )
            match.id
        }
    }

    override suspend fun sitesNear(lat: Double, lon: Double, radiusM: Double): List<StoredSite> {
        val hashes = Geohash.withNeighbors(Geohash.encode(lat, lon, 7)).toMutableSet()
        // radius beyond one cell ring: expand via the corner neighbors' neighbors
        if (radiusM > 150) {
            hashes.toList().forEach { hashes.addAll(Geohash.withNeighbors(it)) }
        }
        val now = System.currentTimeMillis()
        return siteDao.byGeohashes(hashes.toList())
            .asSequence()
            .map { it.toStored(now) }
            .filter { it.confidence >= ALERT_MIN_CONFIDENCE }
            .filter { GpsTrail.haversineMeters(lat, lon, it.lat, it.lon) <= radiusM }
            .toList()
    }

    /** All sites (decayed) for the map screen. */
    fun allSitesFlow(): Flow<List<StoredSite>> = siteDao.allFlow().map { list ->
        val now = System.currentTimeMillis()
        list.map { it.toStored(now) }
    }

    suspend fun allSites(): List<StoredSite> {
        val now = System.currentTimeMillis()
        return siteDao.all().map { it.toStored(now) }
    }

    /** Weekly prune (also run at service start): decayed < 0.15 dies. */
    suspend fun pruneDecayed(): Int {
        val now = System.currentTimeMillis()
        val dead = siteDao.all().filter { decayedConfidence(it.confidence, it.lastSeenMs, now) < ALERT_MIN_CONFIDENCE }
        if (dead.isNotEmpty()) {
            siteDao.delete(dead.map { it.id })
            Timber.tag(TAG).i("pruned %d decayed hazard sites", dead.size)
        }
        return dead.size
    }

    /** Merge one remote (tile-downloaded) site; conservative confidence. */
    suspend fun mergeRemoteSite(type: HazardType, lat: Double, lon: Double, headingDeg: Float, confidence: Float) {
        upsertSite(
            NewHazardEvent(
                type = type,
                confidence = confidence.coerceAtMost(0.8f),
                lat = lat,
                lon = lon,
                headingDeg = headingDeg,
                speedMps = 0f,
                source = com.deepmost.rabbitav.core.hazard.HazardSource.FUSED,
                timeMs = System.currentTimeMillis(),
                tripId = 0,
            )
        )
    }

    private fun HazardSiteEntity.toStored(nowMs: Long) = StoredSite(
        id = id,
        type = runCatching { HazardType.valueOf(type) }.getOrDefault(HazardType.UNKNOWN),
        lat = lat,
        lon = lon,
        headingDeg = headingDeg,
        confidence = decayedConfidence(confidence, lastSeenMs, nowMs),
        hitCount = hitCount,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        avgSpeedMps = avgSpeedMps,
    )

    private fun circularMix(aDeg: Float, bDeg: Float, weightB: Float): Float {
        val ar = Math.toRadians(aDeg.toDouble())
        val br = Math.toRadians(bDeg.toDouble())
        val x = (1 - weightB) * kotlin.math.cos(ar) + weightB * kotlin.math.cos(br)
        val y = (1 - weightB) * kotlin.math.sin(ar) + weightB * kotlin.math.sin(br)
        return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    companion object {
        private const val TAG = "RAV-Hazard"

        /** Cluster radius (Section 5.7): 15 m. */
        const val CLUSTER_RADIUS_M = 15.0

        /** Cluster heading gate (Section 5.7): 45 deg. */
        const val CLUSTER_HEADING_DEG = 45f

        const val MAX_CONFIDENCE = 0.99f

        /** Sites below this (after decay) are ignored and pruned (Section 5.7). */
        const val ALERT_MIN_CONFIDENCE = 0.15f

        /** Half-life of site confidence (Section 5.7): 30 days. */
        const val HALF_LIFE_MS = 30L * 24 * 3600 * 1000

        const val POSITION_EASE = 0.25

        fun decayedConfidence(confidence: Float, lastSeenMs: Long, nowMs: Long): Float {
            val dt = (nowMs - lastSeenMs).coerceAtLeast(0)
            return confidence * 0.5f.pow(dt.toFloat() / HALF_LIFE_MS)
        }
    }
}

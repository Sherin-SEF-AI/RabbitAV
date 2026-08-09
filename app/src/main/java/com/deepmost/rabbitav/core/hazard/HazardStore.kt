package com.deepmost.rabbitav.core.hazard

import com.deepmost.rabbitav.core.imu.HazardType

/** How a hazard event entered the system (also the sync `source` field). */
enum class HazardSource { IMU, FUSED, MANUAL }

/** A finished, geotagged hazard observation ready for storage + sync. */
data class NewHazardEvent(
    val type: HazardType,
    val confidence: Float,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val speedMps: Float,
    val source: HazardSource,
    val timeMs: Long,
    val tripId: Long,
)

/** A stored, clustered hazard site as seen by runtime consumers. */
data class StoredSite(
    val id: Long,
    val type: HazardType,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    /** Decayed confidence (30-day half-life applied on read; Section 5.7). */
    val confidence: Float,
    val hitCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val avgSpeedMps: Float,
)

/**
 * Storage abstraction the core hazard logic talks to; implemented by the Room
 * repository in core/data. Keeps core free of persistence imports.
 */
interface HazardStore {
    /** Records the event and upserts its cluster site; returns the site id. */
    suspend fun recordEvent(event: NewHazardEvent): Long

    /** Decay-weighted sites within [radiusM] of the position (alertable only). */
    suspend fun sitesNear(lat: Double, lon: Double, radiusM: Double): List<StoredSite>
}

package com.deepmost.rabbitav.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val distanceM: Double = 0.0,
    val maxSpeedMps: Float = 0f,
    val movingTimeS: Long = 0,
    val fcwCount: Int = 0,
    val headwayCount: Int = 0,
    val vruCount: Int = 0,
    val hazardAheadCount: Int = 0,
    val hazardsLogged: Int = 0,
    /** DriveMode name: FULL_ADAS / POCKET / REPLAY. */
    val mode: String = "FULL_ADAS",
)

@Entity(
    tableName = "alert_events",
    indices = [Index("tripId"), Index("timeMs")],
)
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timeMs: Long,
    val kind: String,
    val level: String,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val ttcS: Float,
    val distanceM: Float,
)

@Entity(
    tableName = "hazard_events",
    indices = [Index("tripId"), Index("synced"), Index("siteId")],
)
data class HazardEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timeMs: Long,
    val type: String,
    val confidence: Float,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val speedMps: Float,
    val source: String,
    val siteId: Long = 0,
    val synced: Boolean = false,
)

@Entity(
    tableName = "hazard_sites",
    indices = [Index("geohash7"), Index("lastSeenMs")],
)
data class HazardSiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val geohash7: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    /** Confidence AS OF lastSeenMs; decay is applied on read (Section 5.7). */
    val confidence: Float,
    val hitCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val avgSpeedMps: Float,
    /** True when the site came from a hazard-tile download, not this device. */
    val remote: Boolean = false,
)

@Entity(tableName = "calibration_profiles")
data class CalibrationProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val preset: String,
    val cameraHeightM: Float,
    val pitchRad: Float,
    val createdMs: Long,
    val updatedMs: Long,
    val active: Boolean = false,
)

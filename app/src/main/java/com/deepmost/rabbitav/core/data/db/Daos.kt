package com.deepmost.rabbitav.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startMs DESC LIMIT 200")
    fun recentTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    suspend fun openTrip(): TripEntity?
}

@Dao
interface AlertEventDao {
    @Insert
    suspend fun insert(event: AlertEventEntity): Long

    @Query("SELECT * FROM alert_events WHERE tripId = :tripId ORDER BY timeMs")
    suspend fun forTrip(tripId: Long): List<AlertEventEntity>
}

@Dao
interface HazardEventDao {
    @Insert
    suspend fun insert(event: HazardEventEntity): Long

    @Query("SELECT * FROM hazard_events WHERE tripId = :tripId ORDER BY timeMs")
    suspend fun forTrip(tripId: Long): List<HazardEventEntity>

    @Query("SELECT * FROM hazard_events WHERE synced = 0 ORDER BY timeMs LIMIT :limit")
    suspend fun unsynced(limit: Int): List<HazardEventEntity>

    @Query("UPDATE hazard_events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM hazard_events WHERE synced = 0")
    suspend fun unsyncedCount(): Int
}

@Dao
interface HazardSiteDao {
    @Insert
    suspend fun insert(site: HazardSiteEntity): Long

    @Update
    suspend fun update(site: HazardSiteEntity)

    @Query("SELECT * FROM hazard_sites WHERE geohash7 IN (:hashes)")
    suspend fun byGeohashes(hashes: List<String>): List<HazardSiteEntity>

    @Query("SELECT * FROM hazard_sites")
    suspend fun all(): List<HazardSiteEntity>

    @Query("SELECT * FROM hazard_sites")
    fun allFlow(): Flow<List<HazardSiteEntity>>

    @Query("DELETE FROM hazard_sites WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM hazard_sites")
    suspend fun count(): Int
}

@Dao
interface CalibrationDao {
    @Insert
    suspend fun insert(profile: CalibrationProfileEntity): Long

    @Update
    suspend fun update(profile: CalibrationProfileEntity)

    @Query("SELECT * FROM calibration_profiles ORDER BY updatedMs DESC")
    fun profiles(): Flow<List<CalibrationProfileEntity>>

    @Query("SELECT * FROM calibration_profiles WHERE active = 1 LIMIT 1")
    suspend fun activeProfile(): CalibrationProfileEntity?

    @Query("SELECT * FROM calibration_profiles WHERE active = 1 LIMIT 1")
    fun activeProfileFlow(): Flow<CalibrationProfileEntity?>

    @Query("SELECT * FROM calibration_profiles WHERE id = :id")
    suspend fun byId(id: Long): CalibrationProfileEntity?

    @Query("UPDATE calibration_profiles SET active = 0")
    suspend fun clearActive()

    @Query("DELETE FROM calibration_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

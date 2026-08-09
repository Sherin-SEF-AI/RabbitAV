package com.deepmost.rabbitav.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        AlertEventEntity::class,
        HazardEventEntity::class,
        HazardSiteEntity::class,
        CalibrationProfileEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RabbitAvDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun hazardEventDao(): HazardEventDao
    abstract fun hazardSiteDao(): HazardSiteDao
    abstract fun calibrationDao(): CalibrationDao
}

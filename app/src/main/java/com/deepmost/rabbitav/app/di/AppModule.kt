package com.deepmost.rabbitav.app.di

import android.content.Context
import androidx.room.Room
import com.deepmost.rabbitav.core.data.db.AlertEventDao
import com.deepmost.rabbitav.core.data.db.CalibrationDao
import com.deepmost.rabbitav.core.data.db.HazardEventDao
import com.deepmost.rabbitav.core.data.db.HazardSiteDao
import com.deepmost.rabbitav.core.data.db.RabbitAvDatabase
import com.deepmost.rabbitav.core.data.db.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): RabbitAvDatabase =
        Room.databaseBuilder(context, RabbitAvDatabase::class.java, "rabbitav.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun tripDao(db: RabbitAvDatabase): TripDao = db.tripDao()
    @Provides fun alertEventDao(db: RabbitAvDatabase): AlertEventDao = db.alertEventDao()
    @Provides fun hazardEventDao(db: RabbitAvDatabase): HazardEventDao = db.hazardEventDao()
    @Provides fun hazardSiteDao(db: RabbitAvDatabase): HazardSiteDao = db.hazardSiteDao()
    @Provides fun calibrationDao(db: RabbitAvDatabase): CalibrationDao = db.calibrationDao()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

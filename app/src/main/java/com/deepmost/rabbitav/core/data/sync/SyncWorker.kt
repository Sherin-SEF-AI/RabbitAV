package com.deepmost.rabbitav.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepmost.rabbitav.BuildConfig
import com.deepmost.rabbitav.core.data.db.HazardEventDao
import com.deepmost.rabbitav.core.data.repo.HazardRepository
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import com.deepmost.rabbitav.core.hazard.Geohash
import com.deepmost.rabbitav.core.imu.HazardType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Daily events-only sync (Section 5.8): unmetered + battery-not-low. Pushes
 * unsynced hazard events in batches, pulls the hazard tile around the last
 * known position, prunes decayed sites while it is awake.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val hazardEventDao: HazardEventDao,
    private val hazardRepository: HazardRepository,
    private val settings: SettingsRepository,
    private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val client = SyncClient(okHttpClient, BuildConfig.SYNC_BASE_URL)
        if (!client.enabled) {
            Timber.tag(TAG).i("sync base URL empty; worker exits")
            return Result.success()
        }
        if (!settings.syncEnabled.first()) {
            Timber.tag(TAG).i("sync toggle off; worker exits")
            return Result.success()
        }

        hazardRepository.pruneDecayed()

        // ---- push events ----
        val pending = hazardEventDao.unsynced(BATCH_LIMIT)
        if (pending.isNotEmpty()) {
            val batch = SyncClient.EventBatch(
                deviceId = settings.deviceId(),
                events = pending.map {
                    SyncClient.HazardEventDto(
                        type = it.type,
                        lat = it.lat,
                        lon = it.lon,
                        heading = it.headingDeg,
                        speed = it.speedMps,
                        confidence = it.confidence,
                        source = it.source.lowercase(),
                        ts = it.timeMs,
                    )
                },
            )
            val result = client.postEvents(batch)
            if (result.isSuccess) {
                hazardEventDao.markSynced(pending.map { it.id })
            } else {
                Timber.tag(TAG).w(result.exceptionOrNull(), "event push failed; will retry")
                return Result.retry()
            }
        }

        // ---- pull tile around the last known position ----
        settings.lastPosition()?.let { (lat, lon) ->
            val tile = client.getTile(Geohash.encode(lat, lon, 5))
            tile.onSuccess { sites ->
                for (s in sites) {
                    hazardRepository.mergeRemoteSite(
                        type = runCatching { HazardType.valueOf(s.type.uppercase()) }.getOrDefault(HazardType.UNKNOWN),
                        lat = s.lat,
                        lon = s.lon,
                        headingDeg = s.heading,
                        confidence = s.confidence,
                    )
                }
                if (sites.isNotEmpty()) Timber.tag(TAG).i("merged %d remote sites", sites.size)
            }.onFailure {
                Timber.tag(TAG).w(it, "tile pull failed (non-fatal)")
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RAV-Sync"
        private const val WORK_NAME = "rabbitav-sync"
        const val BATCH_LIMIT = 500

        /** Idempotent daily schedule; call at app start and on toggle-on. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

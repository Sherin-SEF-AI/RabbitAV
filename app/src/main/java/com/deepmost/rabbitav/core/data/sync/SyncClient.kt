package com.deepmost.rabbitav.core.data.sync

import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Events-only, metadata-only sync REST contract (Section 5.8). No imagery,
 * ever. Disabled entirely when [baseUrl] is empty (BuildConfig.SYNC_BASE_URL).
 * Retries/backoff live in WorkManager; this client is single-attempt.
 */
class SyncClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    val enabled: Boolean get() = baseUrl.isNotBlank()

    @Serializable
    data class HazardEventDto(
        val type: String,
        val lat: Double,
        val lon: Double,
        val heading: Float,
        val speed: Float,
        val confidence: Float,
        val source: String, // imu | fused | manual
        val ts: Long,
    )

    @Serializable
    data class EventBatch(
        val deviceId: String,
        val events: List<HazardEventDto>,
    )

    @Serializable
    data class PostResponse(val accepted: Int = 0)

    @Serializable
    data class TileSite(
        val type: String,
        val lat: Double,
        val lon: Double,
        val heading: Float = 0f,
        val confidence: Float,
        val hits: Int = 1,
        @SerialName("lastSeen") val lastSeenMs: Long = 0,
    )

    @Serializable
    data class TileResponse(val sites: List<TileSite> = emptyList())

    /** POST /v1/hazard-events with a gzip JSON body. Returns accepted count. */
    fun postEvents(batch: EventBatch): Result<Int> {
        if (!enabled) return Result.failure(IllegalStateException("sync disabled"))
        return runCatching {
            val payload = json.encodeToString(EventBatch.serializer(), batch).encodeToByteArray()
            val gz = okio.Buffer().let { buf ->
                GZIPOutputStream(buf.outputStream()).use { it.write(payload) }
                buf.readByteArray()
            }
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/hazard-events")
                .header("Content-Encoding", "gzip")
                .post(gz.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("POST hazard-events -> HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val accepted = runCatching {
                    json.decodeFromString(PostResponse.serializer(), body).accepted
                }.getOrDefault(batch.events.size)
                Timber.tag(TAG).i("synced %d hazard events (accepted=%d)", batch.events.size, accepted)
                accepted
            }
        }
    }

    /** GET /v1/hazard-tiles/{geohash5}: aggregated sites for pre-warning. */
    fun getTile(geohash5: String): Result<List<TileSite>> {
        if (!enabled) return Result.failure(IllegalStateException("sync disabled"))
        return runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/hazard-tiles/$geohash5")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@use emptyList()
                if (!resp.isSuccessful) throw IOException("GET hazard-tiles -> HTTP ${resp.code}")
                json.decodeFromString(TileResponse.serializer(), resp.body?.string().orEmpty()).sites
            }
        }
    }

    companion object {
        private const val TAG = "RAV-Sync"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

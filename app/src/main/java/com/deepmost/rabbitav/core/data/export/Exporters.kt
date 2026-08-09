package com.deepmost.rabbitav.core.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.deepmost.rabbitav.core.data.repo.HazardRepository
import com.deepmost.rabbitav.core.hazard.StoredSite
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * GeoJSON + CSV export of HazardSites via the share sheet (Section 5.8).
 * GeoJSON properties are complete — this is the future B2G artifact.
 */
@Singleton
class HazardExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hazardRepository: HazardRepository,
) {
    suspend fun exportGeoJson(): File = withContext(Dispatchers.IO) {
        val sites = hazardRepository.allSites()
        val fc = buildJsonObject {
            put("type", "FeatureCollection")
            put("generated", isoNow())
            put("generator", "RabbitAV")
            put("features", buildJsonArray {
                for (s in sites) add(featureOf(s))
            })
        }
        writeExport("rabbitav_hazards_${stamp()}.geojson", fc.toString())
    }

    private fun featureOf(s: StoredSite) = buildJsonObject {
        put("type", "Feature")
        put("geometry", buildJsonObject {
            put("type", "Point")
            put("coordinates", buildJsonArray {
                add(JsonPrimitive(s.lon))
                add(JsonPrimitive(s.lat))
            })
        })
        put("properties", buildJsonObject {
            put("type", s.type.name)
            put("confidence", s.confidence)
            put("hits", s.hitCount)
            put("heading_deg", s.headingDeg)
            put("first_seen", iso(s.firstSeenMs))
            put("last_seen", iso(s.lastSeenMs))
            put("avg_trigger_speed_kmh", s.avgSpeedMps * 3.6f)
        })
    }

    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val sites = hazardRepository.allSites()
        val sb = StringBuilder()
        sb.append("type,lat,lon,confidence,hits,heading_deg,first_seen,last_seen,avg_trigger_speed_kmh\n")
        for (s in sites) {
            sb.append(s.type.name).append(',')
                .append(s.lat).append(',')
                .append(s.lon).append(',')
                .append("%.3f".format(Locale.US, s.confidence)).append(',')
                .append(s.hitCount).append(',')
                .append("%.1f".format(Locale.US, s.headingDeg)).append(',')
                .append(iso(s.firstSeenMs)).append(',')
                .append(iso(s.lastSeenMs)).append(',')
                .append("%.1f".format(Locale.US, s.avgSpeedMps * 3.6f)).append('\n')
        }
        writeExport("rabbitav_hazards_${stamp()}.csv", sb.toString())
    }

    /** Writes a text export and returns the file (exports/ under cacheDir). */
    private fun writeExport(name: String, content: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name)
        f.writeText(content)
        Timber.tag("RAV-Data").i("export written: %s (%d B)", f, f.length())
        return f
    }

    /** Share-sheet intent for an exported file (or a log dump etc.). */
    fun shareIntent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Writes arbitrary text (log ring export) into the shareable dir. */
    fun writeTextForShare(name: String, content: String): File = writeExport(name, content)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(ms))
    private fun isoNow(): String = iso(System.currentTimeMillis())
}

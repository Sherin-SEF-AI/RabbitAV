package com.deepmost.rabbitav.core.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deepmost.rabbitav.core.alerts.AlertTuning
import com.deepmost.rabbitav.core.inference.DelegateBenchmark
import com.deepmost.rabbitav.core.inference.DelegateKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "rabbitav_settings")

/** All DataStore-backed configuration (Section 2 persistence split). */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val tuningJson = stringPreferencesKey("alert_tuning_json")
        val benchmarkJson = stringPreferencesKey("delegate_benchmark_json")
        val audioVolume = floatPreferencesKey("audio_volume")
        val ttsEnabled = booleanPreferencesKey("tts_enabled")
        val language = stringPreferencesKey("language") // "en" | "hi"
        val syncEnabled = booleanPreferencesKey("sync_enabled")
        val wrongSideEnabled = booleanPreferencesKey("wrong_side_enabled")
        val incidentClipEnabled = booleanPreferencesKey("incident_clip_enabled")
        val deviceId = stringPreferencesKey("device_id")
        val lastLat = floatPreferencesKey("last_lat")
        val lastLon = floatPreferencesKey("last_lon")
        val previewEnabled = booleanPreferencesKey("preview_enabled")
        val debugOverlay = booleanPreferencesKey("debug_overlay")
        val replayVideoPath = stringPreferencesKey("replay_video_path")
        val replayLoop = booleanPreferencesKey("replay_loop")
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.onboardingDone] ?: false }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboardingDone] = true }

    val tuning: Flow<AlertTuning> = context.dataStore.data.map {
        it[Keys.tuningJson]?.let(AlertTuning::fromJson) ?: AlertTuning()
    }

    suspend fun tuningNow(): AlertTuning = tuning.first()
    suspend fun setTuning(t: AlertTuning) = context.dataStore.edit { it[Keys.tuningJson] = t.toJson() }

    val benchmarkReport: Flow<DelegateBenchmark.BenchmarkReport?> = context.dataStore.data.map {
        it[Keys.benchmarkJson]?.let(DelegateBenchmark.BenchmarkReport::fromJson)
    }

    suspend fun benchmarkReportNow(): DelegateBenchmark.BenchmarkReport? = benchmarkReport.first()
    suspend fun setBenchmarkReport(r: DelegateBenchmark.BenchmarkReport) =
        context.dataStore.edit { it[Keys.benchmarkJson] = r.toJson() }

    /** Chosen delegate for the given model, or null when a (re)benchmark is due. */
    suspend fun chosenDelegateFor(modelName: String): DelegateKind? {
        val report = benchmarkReportNow() ?: return null
        return if (report.modelName == modelName) report.winner else null
    }

    val audioVolume: Flow<Float> = context.dataStore.data.map { it[Keys.audioVolume] ?: 1f }
    suspend fun setAudioVolume(v: Float) = context.dataStore.edit { it[Keys.audioVolume] = v.coerceIn(0f, 1f) }

    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.ttsEnabled] ?: true }
    suspend fun setTtsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.ttsEnabled] = v }

    val language: Flow<String> = context.dataStore.data.map { it[Keys.language] ?: "en" }
    suspend fun setLanguage(lang: String) = context.dataStore.edit { it[Keys.language] = lang }

    val syncEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.syncEnabled] ?: false }
    suspend fun setSyncEnabled(v: Boolean) = context.dataStore.edit { it[Keys.syncEnabled] = v }

    val wrongSideEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.wrongSideEnabled] ?: false }
    suspend fun setWrongSideEnabled(v: Boolean) = context.dataStore.edit { it[Keys.wrongSideEnabled] = v }

    val incidentClipEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.incidentClipEnabled] ?: false }
    suspend fun setIncidentClipEnabled(v: Boolean) = context.dataStore.edit { it[Keys.incidentClipEnabled] = v }

    val previewEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.previewEnabled] ?: true }
    suspend fun setPreviewEnabled(v: Boolean) = context.dataStore.edit { it[Keys.previewEnabled] = v }

    val debugOverlay: Flow<Boolean> = context.dataStore.data.map { it[Keys.debugOverlay] ?: false }
    suspend fun setDebugOverlay(v: Boolean) = context.dataStore.edit { it[Keys.debugOverlay] = v }

    val replayVideoPath: Flow<String> = context.dataStore.data.map { it[Keys.replayVideoPath] ?: "" }
    suspend fun setReplayVideoPath(p: String) = context.dataStore.edit { it[Keys.replayVideoPath] = p }

    val replayLoop: Flow<Boolean> = context.dataStore.data.map { it[Keys.replayLoop] ?: true }
    suspend fun setReplayLoop(v: Boolean) = context.dataStore.edit { it[Keys.replayLoop] = v }

    /** Random, user-resettable sync identity (Section 5.8). */
    suspend fun deviceId(): String {
        val current = context.dataStore.data.first()[Keys.deviceId]
        if (current != null) return current
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.deviceId] = fresh }
        return fresh
    }

    suspend fun resetDeviceId(): String {
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.deviceId] = fresh }
        return fresh
    }

    suspend fun setLastPosition(lat: Double, lon: Double) = context.dataStore.edit {
        it[Keys.lastLat] = lat.toFloat()
        it[Keys.lastLon] = lon.toFloat()
    }

    suspend fun lastPosition(): Pair<Double, Double>? {
        val prefs = context.dataStore.data.first()
        val lat = prefs[Keys.lastLat] ?: return null
        val lon = prefs[Keys.lastLon] ?: return null
        return lat.toDouble() to lon.toDouble()
    }
}

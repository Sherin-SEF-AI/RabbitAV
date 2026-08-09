package com.deepmost.rabbitav.core.alerts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every ADAS threshold in one persisted object (Section 5.5), editable in
 * Settings > Advanced. Comments give the control meaning and sane range.
 */
@Serializable
data class AlertTuning(
    // --- global gates ---
    /** All ADAS alerts disabled below this ego speed (km/h). Range 5-20. */
    val globalMinSpeedKmh: Float = 10f,

    // --- FCW ---
    /** Ego speed gate for FCW on vehicles (km/h). Range 10-30. */
    val fcwMinSpeedKmh: Float = 15f,
    /** Ego speed gate for FCW on VRU/animal targets (km/h). Range 5-20. */
    val fcwMinSpeedVruKmh: Float = 10f,
    /** Closing speed precondition (m/s). Range 0.5-3. */
    val fcwMinClosingMps: Float = 1.5f,
    /** TTC for CAUTION (s). Range 1.8-4. */
    val fcwTtcCautionS: Float = 2.5f,
    /** TTC for CRITICAL (s). Range 1.0-2.2, must be < caution. */
    val fcwTtcCriticalS: Float = 1.6f,

    // --- Headway ---
    /** Ego speed gate for headway monitoring (km/h). Range 20-50. */
    val headwayMinSpeedKmh: Float = 30f,
    /** Lead must be stable in corridor this long (s). Range 0.5-3. */
    val headwayStableS: Float = 1.0f,
    /** ADVISORY below this headway (s). Range 0.8-2.0. */
    val headwayAdvisoryS: Float = 1.0f,
    /** WARNING below this headway (s). Range 0.4-0.9. */
    val headwayWarningS: Float = 0.6f,
    /** Min gap between ADVISORY chimes (s). Range 5-30. */
    val headwayAdvisoryCooldownS: Float = 10f,

    // --- VRU proximity ---
    /** Fixed floor of the VRU alert radius (m). Range 5-15. */
    val vruMinRadiusM: Float = 8f,
    /** Speed-scaled VRU radius: egoSpeed * this (s). Range 1.0-3.0. */
    val vruTimeRadiusS: Float = 1.8f,

    // --- Visual road hazard (capability-gated) ---
    /** Alert when hazard closer than egoSpeed * this (s). Range 1.5-4. */
    val hazardVisualTimeS: Float = 2.5f,

    // --- Mapped hazard approach ---
    /** Approach alert boundary (m). Range 60-250. */
    val hazardApproachM: Float = 120f,
    /** Bearing gate to the site (deg). Range 20-90. */
    val hazardBearingGateDeg: Float = 45f,
    /** Ego speed gate for approach alerts (km/h). Range 10-40. */
    val hazardApproachMinSpeedKmh: Float = 20f,

    // --- Wrong-side (stretch, default off via settings flag) ---
    /** Oncoming closing margin over ego speed (m/s). Range 2-6. */
    val wrongSideClosingMarginMps: Float = 3f,
    /** Sustain time before firing (s). Range 0.5-1.5. */
    val wrongSideSustainS: Float = 0.8f,

    // --- Hysteresis & cooldowns (Section 5.5) ---
    /** A level must hold this long to fire (s). Range 0.2-1.0. */
    val holdToFireS: Float = 0.4f,
    /** Condition must clear this long to downgrade (s). Range 0.5-2. */
    val holdToClearS: Float = 1.0f,
    /** Per-track, per-kind, per-level re-fire cooldown (s). Range 2-10. */
    val perTrackCooldownS: Float = 4.0f,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): AlertTuning =
            runCatching { json.decodeFromString(serializer(), s) }.getOrDefault(AlertTuning())
    }
}

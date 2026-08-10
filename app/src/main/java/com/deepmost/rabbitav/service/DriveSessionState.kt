package com.deepmost.rabbitav.service

import com.deepmost.rabbitav.core.alerts.ActiveAlert
import com.deepmost.rabbitav.core.governor.PerfGovernor
import com.deepmost.rabbitav.core.inference.DelegateKind
import com.deepmost.rabbitav.core.tracking.TrackSnapshot

/** Operating modes (Sections 5.11, 5.12). */
enum class DriveMode { FULL_ADAS, POCKET, REPLAY }

/** Why ADAS alerts are currently inactive (HUD must always show why). */
enum class AdasInactiveReason {
    NONE, // active
    NOT_RUNNING,
    BENCHMARKING,
    NO_CALIBRATION,
    NO_GPS,
    LOW_SPEED,
    GOVERNOR_SUSPENDED,
    POCKET_MODE,
}

/** Everything the drive HUD renders, published at 25 Hz (decimated by UI). */
data class HudState(
    val running: Boolean = false,
    val mode: DriveMode = DriveMode.FULL_ADAS,
    val speedKmh: Float = 0f,
    val speedValid: Boolean = false,
    val calibrationValid: Boolean = false,
    val adasActive: Boolean = false,
    val adasInactiveReason: AdasInactiveReason = AdasInactiveReason.NOT_RUNNING,
    val topAlert: ActiveAlert? = null,
    /** Headway to the corridor lead vehicle (s), +inf when none. */
    val leadHeadwayS: Float = Float.POSITIVE_INFINITY,
    val leadDistanceM: Float = Float.NaN,
    val governorLevel: PerfGovernor.Level = PerfGovernor.Level.L0,
    val hazardsThisTrip: Int = 0,
    val tripDistanceKm: Float = 0f,
    val synthetic: Boolean = false,
    /** Mount pitch drifted > 3 deg from the active profile (Section 5.9). */
    val calibrationDrift: Boolean = false,
)

/** Geometry overlays precomputed for the debug HUD painter (Section 5.9). */
data class CorridorOverlay(
    val horizonVNorm: Float,
    /** Triple rows at 10/25/50 m: [vNorm, uLeftNorm, uRightNorm]. */
    val rungs: List<FloatArray>,
)

/** Per-tick overlay payload for the debug overlay painter. */
data class OverlayFrame(
    val tracks: List<TrackSnapshot> = emptyList(),
    val corridor: CorridorOverlay? = null,
    val timestampNs: Long = 0,
    /** Upright analysis frame aspect (w/h); aligns overlay with FIT_CENTER preview. */
    val aspect: Float = 4f / 3f,
)

/** Live performance counters for HUD footer + debug screen. */
data class PerfStats(
    val detectorFps: Float = 0f,
    val cameraFps: Float = 0f,
    val p50Ms: Float = 0f,
    val p90Ms: Float = 0f,
    val delegate: DelegateKind = DelegateKind.XNNPACK,
    val dropRatio: Float = 0f,
    val totalMemMb: Float = 0f,
    val thermalPressure: Float = 0f,
    val batteryTempC: Float = Float.NaN,
    val governorLevel: PerfGovernor.Level = PerfGovernor.Level.L0,
    val modelName: String = "",
    val inputSize: Int = 0,
    val benchmarking: Boolean = false,
)

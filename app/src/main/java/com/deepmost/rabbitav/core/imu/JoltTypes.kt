package com.deepmost.rabbitav.core.imu

/** Road hazard taxonomy for the mapper. WATERLOGGING is manual-report-only in v1. */
enum class HazardType {
    POTHOLE,
    SPEED_BREAKER,
    ROUGH_PATCH,
    WATERLOGGING,
    UNKNOWN,
}

/** Features extracted from one 1.5 s jolt window (Section 5.6). */
data class JoltFeatures(
    val peakPositive: Float,
    val peakNegative: Float, // magnitude (positive number)
    val negativeFirst: Boolean,
    val durationAboveHalfPeakS: Float,
    val energy: Float,
    val doubleBump: Boolean,
    val doubleBumpSymmetric: Boolean,
    val doubleBumpGapS: Float,
    /** Distinct positive peaks above 60% of the maximum (>=0.12 s apart). A
     *  breaker shows exactly 2 (front/rear axle); washboard shows many. */
    val positivePeakCount: Int,
    val windowRms: Float,
    val gyroPitchRateRange: Float, // rad/s peak-to-peak; NaN when no gyroscope
)

/** Output of the IMU engine, input to vision fusion (Section 5.7). */
data class HazardCandidate(
    val type: HazardType,
    /** 0..1; capped at 0.7 when no gyroscope exists (Section 5.6). */
    val imuConfidence: Float,
    /** Event (peak) timestamp on the elapsedRealtimeNanos clock. */
    val timestampNs: Long,
    val features: JoltFeatures?,
    /** Ego speed at trigger (m/s); used for lookback + geotag math. */
    val egoSpeedMps: Float,
)

package com.deepmost.rabbitav.core.geometry

/** Vehicle presets fix the camera height prior (Section 5.4). */
enum class VehiclePreset(val cameraHeightM: Float) {
    HATCHBACK(1.25f),
    SEDAN(1.30f),
    SUV(1.55f),
    CUSTOM(1.30f),
}

/**
 * Active mount calibration used by all geometry. [pitchRad] is positive when
 * the camera looks DOWN toward the road. Yaw is assumed 0 (Section 5.4).
 */
data class CalibrationState(
    val valid: Boolean,
    val preset: VehiclePreset,
    val cameraHeightM: Float,
    val pitchRad: Float,
    val profileName: String = "",
) {
    companion object {
        val INVALID = CalibrationState(
            valid = false,
            preset = VehiclePreset.SEDAN,
            cameraHeightM = VehiclePreset.SEDAN.cameraHeightM,
            pitchRad = 0f,
        )
    }
}

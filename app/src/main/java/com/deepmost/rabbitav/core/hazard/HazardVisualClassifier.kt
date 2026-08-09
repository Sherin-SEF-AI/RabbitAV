package com.deepmost.rabbitav.core.hazard

import com.deepmost.rabbitav.core.camera.LookbackRingBuffer
import com.deepmost.rabbitav.core.imu.HazardType

/** Result of visually classifying a lookback crop. */
data class VisualResult(
    val type: HazardType,
    val confidence: Float,
)

/**
 * Section 5.7 capability gate. The default runtime implementation
 * (DetectorBackedVisualClassifier in the service layer) checks whether the
 * ACTIVE model declares `road_hazard_classification`; if not it returns null
 * and fusion proceeds IMU-only. This is a documented capability gate, not a
 * stub: the IMU path is a complete, shippable detector on its own, and a
 * trained classifier shipped under the model contract lights this up with
 * zero app-code change.
 */
interface HazardVisualClassifier {
    suspend fun classify(crop: LookbackRingBuffer.Snapshot): VisualResult?
}

package com.deepmost.rabbitav.core.geometry

import com.deepmost.rabbitav.core.inference.CanonicalClass
import kotlin.math.atan
import kotlin.math.tan

/**
 * Ground-plane geometry (Section 5.4): IPM distance from the bounding-box
 * bottom edge, width-prior cross-check, lateral offset, and the ego corridor.
 * Pure math over [CameraIntrinsics] + [CalibrationState]; fully unit-tested.
 */
class GroundGeometry(
    val intrinsics: CameraIntrinsics,
    val calibration: CalibrationState,
) {
    /** Result of a distance estimate. */
    class Estimate {
        var zMeters = Float.NaN
        var lowConfidence = false
        var method = Method.NONE

        enum class Method { NONE, IPM, WIDTH_PRIOR, FUSED_MIN }
    }

    /** Horizon row in upright pixels (where phi == 0). */
    fun horizonV(): Float = intrinsics.cy - intrinsics.fy * tan(calibration.pitchRad)

    /**
     * IPM ground distance for a box bottom at normalized row [vNorm].
     * Returns NaN when the ray points above (or within 0.5 deg of) the horizon.
     */
    fun ipmDistance(vNorm: Float): Float {
        val vPx = vNorm * intrinsics.height
        val phi = atan((vPx - intrinsics.cy) / intrinsics.fy) + calibration.pitchRad
        if (phi <= MIN_PHI_RAD) return Float.NaN
        return calibration.cameraHeightM / tan(phi)
    }

    /** Row (normalized) where the ground at distance Z projects — overlay rungs. */
    fun vForDistance(zMeters: Float): Float {
        val phi = atan(calibration.cameraHeightM / zMeters)
        val vPx = intrinsics.cy + intrinsics.fy * tan(phi - calibration.pitchRad)
        return vPx / intrinsics.height
    }

    /** Width-prior distance: Zw = fx * W_class / w_px. */
    fun widthPriorDistance(canonical: CanonicalClass, wNorm: Float): Float {
        val wPx = wNorm * intrinsics.width
        if (wPx < 2f) return Float.NaN
        return intrinsics.fx * canonical.widthMeters / wPx
    }

    /** Lateral offset (m) of the box center ray at ground distance Z. */
    fun lateralOffset(zMeters: Float, uNorm: Float): Float {
        val uPx = uNorm * intrinsics.width
        return zMeters * (uPx - intrinsics.cx) / intrinsics.fx
    }

    /**
     * Fused estimate per the Section 5.4 policy: IPM primary; width prior when
     * IPM is geometrically invalid or the box is cut off at the frame bottom;
     * when both exist and disagree >35%, take the smaller and flag low
     * confidence (conservative for safety).
     */
    fun estimate(canonical: CanonicalClass, bottomVNorm: Float, wNorm: Float, out: Estimate): Estimate {
        val cutOff = bottomVNorm >= 1f - BOTTOM_CUTOFF_FRACTION
        val zIpm = if (cutOff) Float.NaN else ipmDistance(bottomVNorm)
        val zW = widthPriorDistance(canonical, wNorm)

        out.lowConfidence = false
        when {
            zIpm.isNaN() && zW.isNaN() -> {
                out.zMeters = Float.NaN
                out.method = Estimate.Method.NONE
            }
            zIpm.isNaN() -> {
                out.zMeters = zW
                out.method = Estimate.Method.WIDTH_PRIOR
                out.lowConfidence = true // width priors are class-average guesses
            }
            zW.isNaN() -> {
                out.zMeters = zIpm
                out.method = Estimate.Method.IPM
            }
            else -> {
                val ratio = if (zIpm > zW) zIpm / zW else zW / zIpm
                if (ratio > 1f + DISAGREEMENT_FRACTION) {
                    out.zMeters = minOf(zIpm, zW)
                    out.method = Estimate.Method.FUSED_MIN
                    out.lowConfidence = true
                } else {
                    out.zMeters = zIpm
                    out.method = Estimate.Method.IPM
                }
            }
        }
        return out
    }

    /** Ego corridor half-width by class (Section 5.4). */
    fun corridorHalfWidth(canonical: CanonicalClass): Float =
        if (canonical.isVru) CORRIDOR_HALF_VRU_M else CORRIDOR_HALF_VEHICLE_M

    fun isInCorridor(canonical: CanonicalClass, lateralXM: Float): Boolean =
        kotlin.math.abs(lateralXM) <= corridorHalfWidth(canonical)

    companion object {
        /** Rays flatter than this are numerically useless for IPM (0.5 deg). */
        const val MIN_PHI_RAD = 0.5f * (Math.PI.toFloat() / 180f)

        /** Bbox bottom within 3% of frame bottom counts as cut off. */
        const val BOTTOM_CUTOFF_FRACTION = 0.03f

        /** IPM-vs-width-prior disagreement that triggers conservative fusion. */
        const val DISAGREEMENT_FRACTION = 0.35f

        /** In-path lateral half-widths (m): vehicles 2.0, VRU/animal 2.5. */
        const val CORRIDOR_HALF_VEHICLE_M = 2.0f
        const val CORRIDOR_HALF_VRU_M = 2.5f
    }
}

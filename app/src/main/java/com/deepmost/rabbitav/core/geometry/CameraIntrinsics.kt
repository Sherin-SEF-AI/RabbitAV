package com.deepmost.rabbitav.core.geometry

import kotlin.math.tan
import timber.log.Timber

/**
 * Pinhole intrinsics in the UPRIGHT analysis frame (pixels). Built either from
 * Camera2 characteristics (preferred) or the 66-degree horizontal FOV fallback
 * (Section 5.4); which path was used is recorded in [source] and logged.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
    val source: Source,
) {
    enum class Source { CAMERA2, FOV_FALLBACK }

    companion object {
        private const val TAG = "RAV-Geom"

        /** Fallback horizontal FOV for phones that hide real intrinsics. 60-70 deg typical. */
        const val FALLBACK_HFOV_DEG = 66f

        /**
         * From physical parameters: focal length (mm), sensor size (mm), and
         * the active-array pixels that map onto the analysis stream. The
         * analysis stream is assumed center-cropped from the active array
         * (CameraX default behavior for aspect mismatches).
         */
        fun fromPhysical(
            focalMm: Float,
            sensorWidthMm: Float,
            sensorHeightMm: Float,
            activeW: Int,
            activeH: Int,
            uprightW: Int,
            uprightH: Int,
            rotationDegrees: Int,
        ): CameraIntrinsics {
            // Sensor-frame focal in pixels of the active array.
            val fxActive = focalMm / sensorWidthMm * activeW
            val fyActive = focalMm / sensorHeightMm * activeH

            // The analysis output preserves one axis fully and center-crops the
            // other when aspects differ; scale by the preserved axis.
            val sensorLandscapeW: Int
            val sensorLandscapeH: Int
            val swap = rotationDegrees == 90 || rotationDegrees == 270
            if (swap) {
                sensorLandscapeW = uprightH
                sensorLandscapeH = uprightW
            } else {
                sensorLandscapeW = uprightW
                sensorLandscapeH = uprightH
            }
            val activeAspect = activeW.toFloat() / activeH
            val outAspect = sensorLandscapeW.toFloat() / sensorLandscapeH
            val scale = if (outAspect <= activeAspect) {
                // output is narrower or equal: height fully used
                sensorLandscapeH.toFloat() / activeH
            } else {
                sensorLandscapeW.toFloat() / activeW
            }
            var fxOut = fxActive * scale
            var fyOut = fyActive * scale
            if (swap) {
                val t = fxOut
                fxOut = fyOut
                fyOut = t
            }
            val intr = CameraIntrinsics(
                fx = fxOut, fy = fyOut,
                cx = uprightW / 2f, cy = uprightH / 2f,
                width = uprightW, height = uprightH,
                source = Source.CAMERA2,
            )
            Timber.tag(TAG).i("intrinsics from camera2: %s", intr)
            return intr
        }

        fun fallback(uprightW: Int, uprightH: Int): CameraIntrinsics {
            // Landscape-mounted phones see the road across their wide axis; the
            // FOV prior applies to whichever axis is longer.
            val longAxis = maxOf(uprightW, uprightH).toFloat()
            val f = (longAxis / 2f) / tan(Math.toRadians(FALLBACK_HFOV_DEG / 2.0)).toFloat()
            val intr = CameraIntrinsics(
                fx = f, fy = f,
                cx = uprightW / 2f, cy = uprightH / 2f,
                width = uprightW, height = uprightH,
                source = Source.FOV_FALLBACK,
            )
            Timber.tag(TAG).w("intrinsics FALLBACK (66 deg HFOV): %s", intr)
            return intr
        }
    }
}

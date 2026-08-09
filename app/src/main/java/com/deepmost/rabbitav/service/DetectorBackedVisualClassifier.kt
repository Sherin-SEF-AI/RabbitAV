package com.deepmost.rabbitav.service

import com.deepmost.rabbitav.core.camera.LookbackRingBuffer
import com.deepmost.rabbitav.core.hazard.HazardVisualClassifier
import com.deepmost.rabbitav.core.hazard.VisualResult
import com.deepmost.rabbitav.core.imu.HazardType
import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.core.inference.InferenceEngine
import com.deepmost.rabbitav.core.inference.LetterboxMeta
import com.deepmost.rabbitav.core.inference.SingleSlotExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Section 5.7 default HazardVisualClassifier: gated on the ACTIVE model's
 * `road_hazard_classification` capability. With today's generic COCO model the
 * gate is false and fusion runs IMU-only — by design, not a stub. The moment a
 * trained model with the capability (and POTHOLE/SPEED_BREAKER classes) is
 * imported under the model contract, this classifier runs it on the lookback
 * crop with zero app-code change.
 *
 * Runs are queued on the SAME single-slot inference executor as live frames
 * (strictly serialized), and the engine input padding is restored afterwards
 * so the next live frame's letterbox stays valid.
 */
class DetectorBackedVisualClassifier(
    private val executor: SingleSlotExecutor,
    private val engineProvider: () -> InferenceEngine?,
    private val capabilityProvider: () -> Boolean,
) : HazardVisualClassifier {

    private val meta = LetterboxMeta()

    override suspend fun classify(crop: LookbackRingBuffer.Snapshot): VisualResult? {
        if (!capabilityProvider()) return null
        val engine = engineProvider() ?: return null

        val deferred = CompletableDeferred<VisualResult?>()
        executor.submit {
            try {
                deferred.complete(runClassification(engine, crop))
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "visual classification failed")
                deferred.complete(null)
            }
        }
        // The executor may be mid-inference; one live frame of queueing is fine
        // for an event that happens once per pothole. 1.5 s guards a stall.
        return withTimeoutOrNull(1500) { deferred.await() }
    }

    private fun runClassification(engine: InferenceEngine, crop: LookbackRingBuffer.Snapshot): VisualResult? {
        meta.configure(crop.width, crop.height, engine.inputWidth, engine.inputHeight)
        fillFromRgb(engine, crop.rgb, crop.width, crop.height)
        val frame = engine.run(crop.timestampNs, meta)
        var best: VisualResult? = null
        for (i in 0 until frame.detections.size) {
            val d = frame.detections.items[i]
            val type = when (d.canonical) {
                CanonicalClass.POTHOLE -> HazardType.POTHOLE
                CanonicalClass.SPEED_BREAKER -> HazardType.SPEED_BREAKER
                CanonicalClass.WATERLOGGING -> HazardType.WATERLOGGING
                else -> null
            } ?: continue
            if (best == null || d.score > best.confidence) {
                best = VisualResult(type, d.score)
            }
        }
        // Restore letterbox padding for the live path (different content rect).
        engine.prefillPadding()
        return best
    }

    /** Nearest-neighbor letterbox fill from packed RGB888 (event path). */
    private fun fillFromRgb(engine: InferenceEngine, rgb: ByteArray, w: Int, h: Int) {
        engine.prefillPadding()
        val input = engine.inputBuffer
        val lb = meta
        if (engine.isFloatInput) {
            val lut = engine.floatLut
            val f = input.asFloatBuffer()
            for (y in 0 until lb.contentH) {
                val sy = (y.toLong() * h / lb.contentH).toInt().coerceIn(0, h - 1)
                var dstIdx = ((y + lb.padY) * lb.dstW + lb.padX) * 3
                for (x in 0 until lb.contentW) {
                    val sx = (x.toLong() * w / lb.contentW).toInt().coerceIn(0, w - 1)
                    val si = (sy * w + sx) * 3
                    f.put(dstIdx, lut[rgb[si].toInt() and 0xFF])
                    f.put(dstIdx + 1, lut[rgb[si + 1].toInt() and 0xFF])
                    f.put(dstIdx + 2, lut[rgb[si + 2].toInt() and 0xFF])
                    dstIdx += 3
                }
            }
        } else {
            val lut = engine.quantLut
            for (y in 0 until lb.contentH) {
                val sy = (y.toLong() * h / lb.contentH).toInt().coerceIn(0, h - 1)
                var dstIdx = ((y + lb.padY) * lb.dstW + lb.padX) * 3
                for (x in 0 until lb.contentW) {
                    val sx = (x.toLong() * w / lb.contentW).toInt().coerceIn(0, w - 1)
                    val si = (sy * w + sx) * 3
                    input.put(dstIdx, lut[rgb[si].toInt() and 0xFF])
                    input.put(dstIdx + 1, lut[rgb[si + 1].toInt() and 0xFF])
                    input.put(dstIdx + 2, lut[rgb[si + 2].toInt() and 0xFF])
                    dstIdx += 3
                }
            }
        }
    }

    companion object {
        private const val TAG = "RAV-Hazard"
    }
}

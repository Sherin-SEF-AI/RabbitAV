package com.deepmost.rabbitav.core.inference

import android.content.Context
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * First-launch delegate ladder benchmark (Section 5.2): XNNPACK is the
 * guaranteed baseline; GPU/NNAPI are enabled only if they beat it honestly —
 * correct outputs (validated against the XNNPACK reference by IoU matching),
 * no NaNs, and p50 within 2x of XNNPACK.
 *
 * Runs on the caller's (inference) thread; each candidate engine is created,
 * measured, and closed sequentially to bound memory.
 */
@Singleton
class DelegateBenchmark @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class DelegateResult(
        val kind: DelegateKind,
        val ok: Boolean,
        val p50Ms: Float,
        val p90Ms: Float,
        val detections: Int,
        val reason: String,
    )

    @Serializable
    data class BenchmarkReport(
        val modelName: String,
        val results: List<DelegateResult>,
        val winner: DelegateKind,
        val timestampMs: Long,
    ) {
        fun toJson(): String = json.encodeToString(serializer(), this)

        companion object {
            val json = Json { ignoreUnknownKeys = true }
            fun fromJson(s: String): BenchmarkReport? =
                runCatching { json.decodeFromString(serializer(), s) }.getOrNull()
        }
    }

    private class RefBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val cls: CanonicalClass)

    fun run(model: ModelManager.LoadedModel, numThreads: Int = 4): BenchmarkReport {
        val pixels: IntArray
        val imgW: Int
        val imgH: Int
        context.assets.open(TEST_IMAGE_ASSET).use { s ->
            val bmp = BitmapFactory.decodeStream(s)
                ?: throw IllegalStateException("benchmark image asset undecodable")
            imgW = bmp.width
            imgH = bmp.height
            pixels = IntArray(imgW * imgH)
            bmp.getPixels(pixels, 0, imgW, 0, 0, imgW, imgH)
            bmp.recycle()
        }

        val results = mutableListOf<DelegateResult>()
        var reference: List<RefBox> = emptyList()
        var xnnP50 = Float.MAX_VALUE

        for (kind in listOf(DelegateKind.XNNPACK, DelegateKind.GPU, DelegateKind.NNAPI)) {
            val r = benchmarkOne(kind, model, pixels, imgW, imgH, numThreads, reference, xnnP50)
            results += r
            if (kind == DelegateKind.XNNPACK) {
                if (!r.ok) {
                    // XNNPACK must work; if it doesn't, the app cannot run inference at all.
                    throw IllegalStateException("XNNPACK baseline failed: ${r.reason}")
                }
                xnnP50 = r.p50Ms
                reference = lastDetections
            }
        }

        val winner = results.filter { it.ok }.minByOrNull { it.p50Ms }?.kind ?: DelegateKind.XNNPACK
        val report = BenchmarkReport(model.config.name, results, winner, System.currentTimeMillis())
        Timber.tag(TAG).i("benchmark complete: winner=%s %s", winner, results.joinToString {
            "${it.kind}:${if (it.ok) "%.1fms".format(it.p50Ms) else "DQ(${it.reason})"}"
        })
        return report
    }

    private var lastDetections: List<RefBox> = emptyList()

    private fun benchmarkOne(
        kind: DelegateKind,
        model: ModelManager.LoadedModel,
        pixels: IntArray,
        imgW: Int,
        imgH: Int,
        numThreads: Int,
        reference: List<RefBox>,
        xnnP50: Float,
    ): DelegateResult {
        var engine: InferenceEngine? = null
        try {
            engine = InferenceEngine(model.modelFile, model.config, kind, numThreads)
            engine.prepare()
            val meta = LetterboxMeta().apply { configure(imgW, imgH, engine.inputWidth, engine.inputHeight) }
            fillInputFromArgb(engine, pixels, imgW, imgH, meta)

            repeat(WARMUP_RUNS) { engine.run(0L, meta) }
            val latencies = FloatArray(TIMED_RUNS)
            var frame: DetectionFrame? = null
            for (i in 0 until TIMED_RUNS) {
                frame = engine.run(0L, meta)
                latencies[i] = frame.latencyMs
            }
            latencies.sort()
            val p50 = latencies[TIMED_RUNS / 2]
            val p90 = latencies[(TIMED_RUNS * 9) / 10]
            val dets = frame!!.detections

            // NaN / garbage guard
            for (i in 0 until dets.size) {
                val d = dets.items[i]
                if (!d.cx.isFinite() || !d.cy.isFinite() || !d.w.isFinite() || !d.h.isFinite() || !d.score.isFinite()) {
                    return DelegateResult(kind, false, p50, p90, dets.size, "non-finite output")
                }
            }

            if (kind == DelegateKind.XNNPACK) {
                lastDetections = (0 until dets.size).map {
                    val d = dets.items[it]
                    RefBox(d.cx, d.cy, d.w, d.h, d.canonical)
                }
                return DelegateResult(kind, true, p50, p90, dets.size, "baseline")
            }

            if (p50 > 2f * xnnP50) {
                return DelegateResult(kind, false, p50, p90, dets.size, "slower than 2x XNNPACK")
            }
            if (!matchesReference(dets, reference)) {
                return DelegateResult(kind, false, p50, p90, dets.size, "output mismatch vs XNNPACK")
            }
            return DelegateResult(kind, true, p50, p90, dets.size, "ok")
        } catch (e: DelegateUnavailableException) {
            return DelegateResult(kind, false, 0f, 0f, 0, e.message ?: "unavailable")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "%s benchmark crashed", kind)
            return DelegateResult(kind, false, 0f, 0f, 0, "crash: ${t.javaClass.simpleName}")
        } finally {
            try {
                engine?.close()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "%s engine close failed", kind)
            }
        }
    }

    /** >=50% of reference boxes must have an IoU>=0.5, same-class counterpart. */
    private fun matchesReference(dets: DetectionBuffer, reference: List<RefBox>): Boolean {
        if (reference.isEmpty()) return true // nothing to compare; NaN check already passed
        var matched = 0
        for (ref in reference) {
            for (i in 0 until dets.size) {
                val d = dets.items[i]
                if (d.canonical != ref.cls) continue
                if (iou(ref, d) >= 0.5f) { matched++; break }
            }
        }
        return matched * 2 >= reference.size
    }

    private fun iou(a: RefBox, b: Detection): Float {
        val l = maxOf(a.cx - a.w / 2, b.left)
        val t = maxOf(a.cy - a.h / 2, b.top)
        val r = minOf(a.cx + a.w / 2, b.right)
        val bo = minOf(a.cy + a.h / 2, b.bottom)
        if (r <= l || bo <= t) return 0f
        val inter = (r - l) * (bo - t)
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0f) inter / union else 0f
    }

    /** Nearest-neighbor letterbox fill from ARGB ints; benchmark-only path. */
    private fun fillInputFromArgb(
        engine: InferenceEngine, pixels: IntArray, imgW: Int, imgH: Int, meta: LetterboxMeta,
    ) {
        engine.prefillPadding()
        val input = engine.inputBuffer
        val cw = meta.contentW
        val ch = meta.contentH
        if (engine.isFloatInput) {
            val lut = engine.floatLut
            val f = input.asFloatBuffer()
            for (y in 0 until ch) {
                val sy = (y.toLong() * imgH / ch).toInt().coerceIn(0, imgH - 1)
                var dstIdx = ((y + meta.padY) * meta.dstW + meta.padX) * 3
                for (x in 0 until cw) {
                    val sx = (x.toLong() * imgW / cw).toInt().coerceIn(0, imgW - 1)
                    val p = pixels[sy * imgW + sx]
                    f.put(dstIdx, lut[(p ushr 16) and 0xFF])
                    f.put(dstIdx + 1, lut[(p ushr 8) and 0xFF])
                    f.put(dstIdx + 2, lut[p and 0xFF])
                    dstIdx += 3
                }
            }
        } else {
            val lut = engine.quantLut
            for (y in 0 until ch) {
                val sy = (y.toLong() * imgH / ch).toInt().coerceIn(0, imgH - 1)
                var dstIdx = ((y + meta.padY) * meta.dstW + meta.padX) * 3
                for (x in 0 until cw) {
                    val sx = (x.toLong() * imgW / cw).toInt().coerceIn(0, imgW - 1)
                    val p = pixels[sy * imgW + sx]
                    input.put(dstIdx, lut[(p ushr 16) and 0xFF])
                    input.put(dstIdx + 1, lut[(p ushr 8) and 0xFF])
                    input.put(dstIdx + 2, lut[p and 0xFF])
                    dstIdx += 3
                }
            }
        }
    }

    companion object {
        private const val TAG = "RAV-Infer"
        const val TEST_IMAGE_ASSET = "benchmark/test_scene_640x480.png"
        const val WARMUP_RUNS = 5
        const val TIMED_RUNS = 30
    }
}

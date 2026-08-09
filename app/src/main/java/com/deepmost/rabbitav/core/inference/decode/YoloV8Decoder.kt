package com.deepmost.rabbitav.core.inference.decode

import com.deepmost.rabbitav.core.inference.DetectionBuffer
import com.deepmost.rabbitav.core.inference.LetterboxMeta
import com.deepmost.rabbitav.core.inference.ModelConfig
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import timber.log.Timber

/**
 * Decoder for the single-tensor YOLOv8/YOLO11 head: [1, 4+nc, N] (transposed,
 * the ultralytics TFLite default) or [1, N, 4+nc]. Handles float32 and INT8
 * outputs; INT8 candidates are pre-filtered on the raw quantized value so the
 * dequantize cost is only paid for boxes that can pass the confidence gate.
 */
class YoloV8Decoder(
    numClasses: Int,
    maxCandidatesHint: Int,
) : DetectionDecoder {

    private val nc = numClasses

    // Candidate scratch, preallocated. 512 survivors of the confidence gate is
    // far beyond any real traffic scene; overflow is counted and logged.
    private val cap = CANDIDATE_CAP
    private val cxs = FloatArray(cap)
    private val cys = FloatArray(cap)
    private val ws = FloatArray(cap)
    private val hs = FloatArray(cap)
    private val scores = FloatArray(cap)
    private val classes = IntArray(cap)
    private val keep = BooleanArray(cap)
    private val nms = Nms(cap)

    /** null = not yet sniffed; true = coords are pixels of model input. */
    private var pixelCoords: Boolean? = null
    private var overflowLogged = false

    init {
        require(maxCandidatesHint > 0) { "empty output shape" }
    }

    override fun decode(
        outputs: List<OutputTensorInfo>,
        meta: LetterboxMeta,
        config: ModelConfig,
        out: DetectionBuffer,
    ) {
        val t = outputs[0]
        val shape = t.shape
        require(shape.size == 3) { "yolo_v8 expects rank-3 output, got ${shape.contentToString()}" }
        val attrs = nc + 4
        val transposed = shape[1] == attrs
        val n = if (transposed) shape[2] else shape[1]
        require((if (transposed) shape[1] else shape[2]) == attrs) {
            "output ${shape.contentToString()} does not match $nc classes"
        }

        when (config.decode.coords) {
            "pixels" -> pixelCoords = true
            "normalized" -> pixelCoords = false
        }

        val buf = t.buffer
        buf.rewind()
        var count = 0
        val thresh = config.decode.confThreshold

        if (t.dtype == DataType.FLOAT32) {
            val f = buf.asFloatBuffer()
            count = if (transposed) collectFloatTransposed(f, n, thresh) else collectFloatRows(f, n, attrs, thresh)
        } else {
            // INT8/UINT8: pre-filter with the quantized threshold
            val qThresh = ((thresh / t.scale) + t.zeroPoint).roundToInt()
            count = if (transposed) {
                collectQuantTransposed(buf, n, qThresh, t.scale, t.zeroPoint)
            } else {
                collectQuantRows(buf, n, attrs, qThresh, t.scale, t.zeroPoint)
            }
        }

        // Coordinate space sniff: sticky after the first frame with candidates.
        if (pixelCoords == null && count > 0) {
            var maxC = 0f
            for (i in 0 until count) {
                if (cxs[i] > maxC) maxC = cxs[i]
                if (ws[i] > maxC) maxC = ws[i]
            }
            pixelCoords = maxC > 2.5f
            Timber.tag(TAG).i("coordinate space sniffed: %s (max=%.2f)", if (pixelCoords == true) "pixels" else "normalized", maxC)
        }
        val toPx = if (pixelCoords == true) 1f else meta.dstW.toFloat() // normalized coords scale by input size
        val toPxY = if (pixelCoords == true) 1f else meta.dstH.toFloat()

        val kept = nms.run(cxs, cys, ws, hs, scores, count, config.decode.iouThreshold, keep)
        if (kept == 0) { out.clear(); return }

        out.clear()
        for (i in 0 until count) {
            if (!keep[i]) continue
            val canonical = config.canonicalFor(classes[i]) ?: continue
            val d = out.claim() ?: break
            d.canonical = canonical
            d.rawClassIndex = classes[i]
            d.score = scores[i]
            d.cx = meta.unmapX(cxs[i] * toPx)
            d.cy = meta.unmapY(cys[i] * toPxY)
            d.w = meta.unmapW(ws[i] * toPx)
            d.h = meta.unmapH(hs[i] * toPxY)
            if (d.w <= 0.001f || d.h <= 0.001f) out.unclaim()
        }
    }

    private fun collectFloatTransposed(f: java.nio.FloatBuffer, n: Int, thresh: Float): Int {
        var count = 0
        for (j in 0 until n) {
            var best = 0f
            var bestC = -1
            for (c in 0 until nc) {
                val v = f.get((4 + c) * n + j)
                if (v > best) { best = v; bestC = c }
            }
            if (best < thresh) continue
            if (count >= cap) { logOverflow(); break }
            cxs[count] = f.get(j)
            cys[count] = f.get(n + j)
            ws[count] = f.get(2 * n + j)
            hs[count] = f.get(3 * n + j)
            scores[count] = best
            classes[count] = bestC
            count++
        }
        return count
    }

    private fun collectFloatRows(f: java.nio.FloatBuffer, n: Int, attrs: Int, thresh: Float): Int {
        var count = 0
        for (j in 0 until n) {
            val base = j * attrs
            var best = 0f
            var bestC = -1
            for (c in 0 until nc) {
                val v = f.get(base + 4 + c)
                if (v > best) { best = v; bestC = c }
            }
            if (best < thresh) continue
            if (count >= cap) { logOverflow(); break }
            cxs[count] = f.get(base)
            cys[count] = f.get(base + 1)
            ws[count] = f.get(base + 2)
            hs[count] = f.get(base + 3)
            scores[count] = best
            classes[count] = bestC
            count++
        }
        return count
    }

    private fun collectQuantTransposed(b: ByteBuffer, n: Int, qThresh: Int, s: Float, zp: Int): Int {
        var count = 0
        for (j in 0 until n) {
            var bestQ = Int.MIN_VALUE
            var bestC = -1
            for (c in 0 until nc) {
                val q = b.get((4 + c) * n + j).toInt()
                if (q > bestQ) { bestQ = q; bestC = c }
            }
            if (bestQ < qThresh) continue
            if (count >= cap) { logOverflow(); break }
            cxs[count] = (b.get(j).toInt() - zp) * s
            cys[count] = (b.get(n + j).toInt() - zp) * s
            ws[count] = (b.get(2 * n + j).toInt() - zp) * s
            hs[count] = (b.get(3 * n + j).toInt() - zp) * s
            scores[count] = (bestQ - zp) * s
            classes[count] = bestC
            count++
        }
        return count
    }

    private fun collectQuantRows(b: ByteBuffer, n: Int, attrs: Int, qThresh: Int, s: Float, zp: Int): Int {
        var count = 0
        for (j in 0 until n) {
            val base = j * attrs
            var bestQ = Int.MIN_VALUE
            var bestC = -1
            for (c in 0 until nc) {
                val q = b.get(base + 4 + c).toInt()
                if (q > bestQ) { bestQ = q; bestC = c }
            }
            if (bestQ < qThresh) continue
            if (count >= cap) { logOverflow(); break }
            cxs[count] = (b.get(base).toInt() - zp) * s
            cys[count] = (b.get(base + 1).toInt() - zp) * s
            ws[count] = (b.get(base + 2).toInt() - zp) * s
            hs[count] = (b.get(base + 3).toInt() - zp) * s
            scores[count] = (bestQ - zp) * s
            classes[count] = bestC
            count++
        }
        return count
    }

    private fun logOverflow() {
        if (!overflowLogged) {
            overflowLogged = true
            Timber.tag(TAG).w("candidate cap %d hit; extra boxes dropped this frame", cap)
        }
    }

    companion object {
        private const val TAG = "RAV-Infer"
        const val CANDIDATE_CAP = 512
    }
}

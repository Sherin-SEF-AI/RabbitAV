package com.deepmost.rabbitav.core.inference.decode

import com.deepmost.rabbitav.core.inference.DetectionBuffer
import com.deepmost.rabbitav.core.inference.LetterboxMeta
import com.deepmost.rabbitav.core.inference.ModelConfig

/**
 * Decoder for SSD-family models with the baked-in TFLite_Detection_PostProcess
 * op (four float32 outputs: boxes [1,N,4] as ymin,xmin,ymax,xmax normalized;
 * classes [1,N]; scores [1,N]; count [1]). NMS is inside the graph, so this
 * only thresholds, maps classes, and unmaps the letterbox.
 */
class SsdDecoder : DetectionDecoder {

    override fun decode(
        outputs: List<OutputTensorInfo>,
        meta: LetterboxMeta,
        config: ModelConfig,
        out: DetectionBuffer,
    ) {
        require(outputs.size >= 4) { "ssd expects 4 outputs, got ${outputs.size}" }
        // Standard order: boxes, classes, scores, numDetections. Verify shapes
        // instead of trusting blindly (some converters permute).
        var boxesT = outputs[0]; var classesT = outputs[1]; var scoresT = outputs[2]; var countT = outputs[3]
        if (boxesT.shape.size != 3) {
            // find by signature
            boxesT = outputs.first { it.shape.size == 3 && it.shape[2] == 4 }
            countT = outputs.first { it.shape.size == 1 }
            val flat = outputs.filter { it.shape.size == 2 }
            classesT = flat[0]; scoresT = flat[1]
        }

        val boxes = boxesT.buffer.also { it.rewind() }.asFloatBuffer()
        val cls = classesT.buffer.also { it.rewind() }.asFloatBuffer()
        val scores = scoresT.buffer.also { it.rewind() }.asFloatBuffer()
        val count = countT.buffer.also { it.rewind() }.asFloatBuffer().get(0).toInt()
            .coerceIn(0, boxesT.shape[1])

        out.clear()
        val thresh = config.decode.confThreshold
        val w = meta.dstW.toFloat()
        val h = meta.dstH.toFloat()
        for (i in 0 until count) {
            val score = scores.get(i)
            if (score < thresh) continue
            val canonical = config.canonicalFor(cls.get(i).toInt()) ?: continue
            val ymin = boxes.get(i * 4) * h
            val xmin = boxes.get(i * 4 + 1) * w
            val ymax = boxes.get(i * 4 + 2) * h
            val xmax = boxes.get(i * 4 + 3) * w
            if (xmax <= xmin || ymax <= ymin) continue
            val d = out.claim() ?: break
            d.canonical = canonical
            d.rawClassIndex = cls.get(i).toInt()
            d.score = score
            d.cx = meta.unmapX((xmin + xmax) / 2f)
            d.cy = meta.unmapY((ymin + ymax) / 2f)
            d.w = meta.unmapW(xmax - xmin)
            d.h = meta.unmapH(ymax - ymin)
            if (d.w <= 0.001f || d.h <= 0.001f) out.unclaim()
        }
    }
}

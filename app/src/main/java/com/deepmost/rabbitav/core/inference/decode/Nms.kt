package com.deepmost.rabbitav.core.inference.decode

/**
 * Class-agnostic greedy NMS over parallel primitive arrays. All state is
 * preallocated at [capacity]; zero allocation per call.
 */
class Nms(private val capacity: Int) {

    private val order = IntArray(capacity)
    private val suppressed = BooleanArray(capacity)

    /**
     * @param cx,cy,w,h,score candidate boxes (any consistent coordinate space)
     * @param count live candidates
     * @param iouThreshold suppress boxes with IoU above this vs a kept box
     * @param keep output flags, sized >= count
     * @return number kept
     */
    fun run(
        cx: FloatArray, cy: FloatArray, w: FloatArray, h: FloatArray,
        score: FloatArray, count: Int, iouThreshold: Float, keep: BooleanArray,
    ): Int {
        val n = minOf(count, capacity)
        for (i in 0 until n) {
            order[i] = i
            suppressed[i] = false
            keep[i] = false
        }
        // insertion sort by descending score: n is small (<=512), branch-friendly
        for (i in 1 until n) {
            val v = order[i]
            val s = score[v]
            var j = i - 1
            while (j >= 0 && score[order[j]] < s) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        var kept = 0
        for (oi in 0 until n) {
            val i = order[oi]
            if (suppressed[i]) continue
            keep[i] = true
            kept++
            val l1 = cx[i] - w[i] / 2f; val t1 = cy[i] - h[i] / 2f
            val r1 = cx[i] + w[i] / 2f; val b1 = cy[i] + h[i] / 2f
            val a1 = w[i] * h[i]
            for (oj in oi + 1 until n) {
                val j = order[oj]
                if (suppressed[j]) continue
                val l2 = cx[j] - w[j] / 2f; val t2 = cy[j] - h[j] / 2f
                val r2 = cx[j] + w[j] / 2f; val b2 = cy[j] + h[j] / 2f
                val iw = minOf(r1, r2) - maxOf(l1, l2)
                if (iw <= 0f) continue
                val ih = minOf(b1, b2) - maxOf(t1, t2)
                if (ih <= 0f) continue
                val inter = iw * ih
                val union = a1 + w[j] * h[j] - inter
                if (union > 0f && inter / union > iouThreshold) suppressed[j] = true
            }
        }
        return kept
    }
}

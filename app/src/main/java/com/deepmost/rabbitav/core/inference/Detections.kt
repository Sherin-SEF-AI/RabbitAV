package com.deepmost.rabbitav.core.inference

/** Which execution path produced an inference. */
enum class DelegateKind { XNNPACK, GPU, NNAPI }

/**
 * One detection in NORMALIZED upright-frame coordinates (0..1 relative to the
 * rotated, full analysis frame — letterbox already unmapped). Mutable and
 * pooled: instances live inside [DetectionBuffer] and are overwritten every
 * inference; consumers must copy what they keep (the tracker does).
 */
class Detection {
    var canonical: CanonicalClass = CanonicalClass.UNKNOWN
    var rawClassIndex: Int = -1
    var score: Float = 0f
    var cx: Float = 0f
    var cy: Float = 0f
    var w: Float = 0f
    var h: Float = 0f

    val left: Float get() = cx - w / 2f
    val top: Float get() = cy - h / 2f
    val right: Float get() = cx + w / 2f
    val bottom: Float get() = cy + h / 2f

    fun set(other: Detection) {
        canonical = other.canonical
        rawClassIndex = other.rawClassIndex
        score = other.score
        cx = other.cx; cy = other.cy; w = other.w; h = other.h
    }
}

/**
 * Preallocated detection storage — the hot path never allocates. [size] is the
 * live count; slots beyond it are stale garbage.
 */
class DetectionBuffer(val capacity: Int = MAX_DETECTIONS) {
    val items: Array<Detection> = Array(capacity) { Detection() }
    var size: Int = 0
        private set

    fun clear() { size = 0 }

    /** Next writable slot, or null when full (overflow is counted by caller). */
    fun claim(): Detection? {
        if (size >= capacity) return null
        return items[size++]
    }

    /** Drops the last claimed slot (used when a candidate fails mapping). */
    fun unclaim() { if (size > 0) size-- }

    /** Compacts [items] keeping only indices where [keep] is true. */
    fun filterInPlace(keep: BooleanArray) {
        var w = 0
        for (r in 0 until size) {
            if (keep[r]) {
                if (w != r) items[w].set(items[r])
                w++
            }
        }
        size = w
    }

    companion object {
        /** Post-NMS ceiling; Indian traffic scenes rarely exceed ~40 relevant objects. */
        const val MAX_DETECTIONS = 64
    }
}

/**
 * Result of one detector inference. The [detections] buffer is owned by the
 * engine and reused; the tracker consumes it synchronously on the inference
 * thread before the next run can start.
 */
class DetectionFrame(
    var timestampNs: Long = 0L,
    var latencyMs: Float = 0f,
    var delegate: DelegateKind = DelegateKind.XNNPACK,
    val detections: DetectionBuffer = DetectionBuffer(),
)

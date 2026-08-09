package com.deepmost.rabbitav.core.camera

/**
 * Preallocated ring of downscaled bottom-center RGB crops covering the last
 * ~3 s (Section 5.1). Writes happen on the analyzer thread at <=15 Hz
 * (see DECISIONS.md on decimation); reads happen rarely, on jolt events.
 *
 * Memory: 45 entries x 320x~188x3 B ~= 8 MB, inside the ~10 MB budget.
 */
class LookbackRingBuffer(
    val entryBytes: Int,
    val width: Int,
    val height: Int,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val slots = Array(capacity) { ByteArray(entryBytes) }
    private val times = LongArray(capacity) { Long.MIN_VALUE }
    private var writeIdx = 0
    private val lock = Any()

    class Snapshot(val timestampNs: Long, val rgb: ByteArray, val width: Int, val height: Int)

    /** Fills the next slot via [filler] and stamps it. Analyzer thread. */
    fun write(timestampNs: Long, filler: (ByteArray) -> Unit) {
        synchronized(lock) {
            filler(slots[writeIdx])
            times[writeIdx] = timestampNs
            writeIdx = (writeIdx + 1) % capacity
        }
    }

    /**
     * Copy of the entry nearest [timestampNs], or null when the ring is empty
     * or nothing lies within [toleranceNs]. Event path — the copy allocation
     * is deliberate (the caller keeps it across suspension points).
     */
    fun nearest(timestampNs: Long, toleranceNs: Long = 1_500_000_000L): Snapshot? {
        synchronized(lock) {
            var bestIdx = -1
            var bestDelta = Long.MAX_VALUE
            for (i in 0 until capacity) {
                val t = times[i]
                if (t == Long.MIN_VALUE) continue
                val d = kotlin.math.abs(t - timestampNs)
                if (d < bestDelta) {
                    bestDelta = d
                    bestIdx = i
                }
            }
            if (bestIdx < 0 || bestDelta > toleranceNs) return null
            return Snapshot(times[bestIdx], slots[bestIdx].copyOf(), width, height)
        }
    }

    fun clear() {
        synchronized(lock) {
            for (i in times.indices) times[i] = Long.MIN_VALUE
            writeIdx = 0
        }
    }

    companion object {
        /** 3.0 s coverage at the 15 Hz decimated write rate. */
        const val DEFAULT_CAPACITY = 45
    }
}

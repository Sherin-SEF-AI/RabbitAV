package com.deepmost.rabbitav.core.imu

/**
 * Rolling median absolute deviation of |x| over ~5 s (Section 5.6 trigger
 * noise floor). Samples are decimated to ~50 Hz into a 256-slot ring; the MAD
 * is recomputed lazily at most every [recomputePeriodNs] using a scratch sort
 * (256 floats — microseconds on any phone).
 */
class RollingMad(
    private val capacity: Int = 256,
    private val recomputePeriodNs: Long = 250_000_000L,
) {
    private val ring = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var idx = 0
    private var filled = 0
    private var lastDecimatedNs = 0L
    private var lastComputeNs = 0L
    private var cachedMad = 0f

    /** ~50 Hz decimation interval. */
    private val decimatePeriodNs = 20_000_000L

    fun push(absValue: Float, tNs: Long) {
        if (tNs - lastDecimatedNs < decimatePeriodNs) return
        lastDecimatedNs = tNs
        ring[idx] = absValue
        idx = (idx + 1) % capacity
        if (filled < capacity) filled++
    }

    /** Current MAD estimate (of |signal|, which for a zero-median band-passed
     *  signal is a robust deviation proxy). */
    fun mad(tNs: Long): Float {
        if (filled < 32) return 0f // not enough history; caller uses the absolute floor
        if (tNs - lastComputeNs >= recomputePeriodNs) {
            lastComputeNs = tNs
            System.arraycopy(ring, 0, scratch, 0, filled)
            java.util.Arrays.sort(scratch, 0, filled)
            val median = scratch[filled / 2]
            // MAD proper: median(|x - median|). Reuse scratch.
            for (i in 0 until filled) scratch[i] = kotlin.math.abs(scratch[i] - median)
            java.util.Arrays.sort(scratch, 0, filled)
            cachedMad = scratch[filled / 2]
        }
        return cachedMad
    }

    fun reset() {
        filled = 0
        idx = 0
        cachedMad = 0f
        lastComputeNs = 0L
    }
}

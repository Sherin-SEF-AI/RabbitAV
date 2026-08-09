package com.deepmost.rabbitav.service

import android.os.Debug
import android.os.SystemClock

/**
 * Sliding 10 s window over inference latencies + frame counters; feeds the
 * governor (p90, drop ratio) and the debug screen. Called from the inference
 * thread (record) and the 1 Hz stats tick (snapshot); internally synchronized.
 */
class PerfMonitor {

    private val cap = 256
    private val latMs = FloatArray(cap)
    private val latAtMs = LongArray(cap)
    private var latIdx = 0

    private val frameAtMs = LongArray(512)
    private var frameIdx = 0
    private val analyzedAtMs = LongArray(512)
    private var analyzedIdx = 0

    private val lock = Any()
    private val scratch = FloatArray(cap)

    fun recordInference(latencyMs: Float) {
        synchronized(lock) {
            latMs[latIdx] = latencyMs
            latAtMs[latIdx] = SystemClock.elapsedRealtime()
            latIdx = (latIdx + 1) % cap
        }
    }

    fun recordFrame(analyzed: Boolean) {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            frameAtMs[frameIdx] = now
            frameIdx = (frameIdx + 1) % frameAtMs.size
            if (analyzed) {
                analyzedAtMs[analyzedIdx] = now
                analyzedIdx = (analyzedIdx + 1) % analyzedAtMs.size
            }
        }
    }

    class Snapshot(
        val p50Ms: Float,
        val p90Ms: Float,
        val detectorFps: Float,
        val cameraFps: Float,
        val dropRatio: Float,
        val memMb: Float,
    )

    fun snapshot(): Snapshot {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val horizon = now - WINDOW_MS

            var n = 0
            for (i in 0 until cap) {
                if (latAtMs[i] >= horizon && latMs[i] > 0f) scratch[n++] = latMs[i]
            }
            java.util.Arrays.sort(scratch, 0, n)
            val p50 = if (n > 0) scratch[n / 2] else 0f
            val p90 = if (n > 0) scratch[(n * 9) / 10] else 0f

            var frames = 0
            for (t in frameAtMs) if (t >= horizon) frames++
            var analyzed = 0
            for (t in analyzedAtMs) if (t >= horizon) analyzed++

            val camFps = frames / (WINDOW_MS / 1000f)
            val detFps = analyzed / (WINDOW_MS / 1000f)
            val drop = if (frames > 0) 1f - analyzed.toFloat() / frames else 0f

            val heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576f
            val nativeMb = Debug.getNativeHeapAllocatedSize() / 1048576f

            return Snapshot(p50, p90, detFps, camFps, drop, heapMb + nativeMb)
        }
    }

    fun reset() {
        synchronized(lock) {
            java.util.Arrays.fill(latAtMs, 0L)
            java.util.Arrays.fill(frameAtMs, 0L)
            java.util.Arrays.fill(analyzedAtMs, 0L)
        }
    }

    companion object {
        const val WINDOW_MS = 10_000L
    }
}

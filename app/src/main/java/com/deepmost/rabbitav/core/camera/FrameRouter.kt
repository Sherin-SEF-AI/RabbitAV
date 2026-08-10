package com.deepmost.rabbitav.core.camera

import com.deepmost.rabbitav.core.inference.DetectionFrame
import com.deepmost.rabbitav.core.inference.FramePreprocessor
import com.deepmost.rabbitav.core.inference.InferenceEngine
import com.deepmost.rabbitav.core.inference.SingleSlotExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Section 5.1 FrameRouter: every frame is packed upright and (decimated to
 * ~15 Hz) written into the lookback ring; the newest frame goes to the
 * inference thread whenever it is idle, otherwise it is dropped.
 *
 * Runs on the analyzer thread. Frame hand-off uses the preprocessor's two-slot
 * upright buffer: the slot is claimed on the analyzer thread BEFORE the task
 * is posted, published via [claimedSlot], and the single-slot executor
 * guarantees exactly one outstanding reader.
 */
class FrameRouter(
    private val preprocessor: FramePreprocessor,
    private val executor: SingleSlotExecutor,
    private val onDetections: (DetectionFrame) -> Unit,
    private val onFrameStats: (frameTimestampNs: Long, analyzed: Boolean) -> Unit,
) : FrameConsumer {

    /** Engine is swapped by the pipeline on delegate/model/governor changes. */
    @Volatile var engine: InferenceEngine? = null

    /** Governor gate: when false, frames feed only the lookback ring. */
    @Volatile var detectorEnabled: Boolean = true

    /** Governor FPS cap: minimum interval between inference submissions (0 = uncapped). */
    @Volatile var minSubmitIntervalNs: Long = 0L
    private var lastSubmitNs = 0L

    /** Ring is created lazily once geometry is known (entry size depends on crop). */
    @Volatile var ring: LookbackRingBuffer? = null
        private set

    private val gate = AtomicBoolean(true)
    private var lastRingWriteNs = 0L
    private var lastDiagNs = 0L
    private var dropsBusy = 0L
    private var dropsCapped = 0L
    @Volatile private var claimedSlot: Int = 0

    val framesSeen = AtomicLong(0)
    val framesInferred = AtomicLong(0)
    val framesDropped = AtomicLong(0)

    private val inferenceTask = Runnable {
        val eng = engine ?: return@Runnable
        val slot = claimedSlot
        val ts = claimedTimestampNs
        try {
            preprocessor.fillModelInput(eng, slot)
            val result = eng.run(ts, preprocessor.letterbox)
            framesInferred.incrementAndGet()
            onDetections(result)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "inference failed; frame skipped")
        }
    }

    @Volatile private var claimedTimestampNs: Long = 0L

    override fun onFrame(frame: CameraFrame) {
        try {
            if (!gate.get()) {
                frame.close()
                return
            }
            framesSeen.incrementAndGet()
            val eng = engine
            if (eng == null) {
                frame.close()
                return
            }

            if (!preprocessor.isConfiguredFor(frame, eng.inputWidth, eng.inputHeight)) {
                preprocessor.configure(frame, eng.inputWidth, eng.inputHeight)
                ring = LookbackRingBuffer(
                    entryBytes = preprocessor.ringEntryBytes(),
                    width = preprocessor.ringW,
                    height = preprocessor.ringH,
                )
                Timber.tag(TAG).i(
                    "ring buffer allocated: %d entries x %d B",
                    LookbackRingBuffer.DEFAULT_CAPACITY, preprocessor.ringEntryBytes()
                )
            }

            val ts = frame.timestampNs
            preprocessor.packAndRotate(frame)
            // Planes are copied into our own storage; release the camera buffer
            // immediately so CameraX can refill it during the heavy work.
            frame.close()

            // Lookback ring at <=15 Hz (see DECISIONS.md: decimation)
            if (ts - lastRingWriteNs >= RING_PERIOD_NS) {
                lastRingWriteNs = ts
                ring?.write(ts) { buf -> preprocessor.writeRingCrop(buf) }
            }

            if (!detectorEnabled) {
                onFrameStats(ts, false)
                return
            }

            val analyzed: Boolean
            val busyNow = executor.isBusy
            val capped = minSubmitIntervalNs > 0 && ts - lastSubmitNs < minSubmitIntervalNs
            if (busyNow || capped) {
                // Only this thread submits, so isBusy is authoritative here.
                framesDropped.incrementAndGet()
                if (busyNow) dropsBusy++ else dropsCapped++
                analyzed = false
            } else {
                claimedSlot = preprocessor.claimForInference()
                claimedTimestampNs = ts
                analyzed = executor.trySubmit(inferenceTask)
                if (analyzed) lastSubmitNs = ts
            }
            if (ts - lastDiagNs > 2_000_000_000L) {
                lastDiagNs = ts
                Timber.tag(TAG).d(
                    "router: seen=%d inferred=%d dropBusy=%d dropCap=%d busyNow=%b sinceSubmit=%dms cap=%dms",
                    framesSeen.get(), framesInferred.get(), dropsBusy, dropsCapped,
                    busyNow, (ts - lastSubmitNs) / 1_000_000, minSubmitIntervalNs / 1_000_000
                )
            }
            onFrameStats(ts, analyzed)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "frame routing failed")
            frame.close()
        }
    }

    /** Pauses routing, runs [block] (buffer/engine surgery), resumes. The
     *  brief sleep lets any frame that passed the gate check finish packing
     *  before buffers are torn down (frames arrive ~33 ms apart; packing takes
     *  ~2 ms, so 60 ms guarantees quiescence). */
    fun reconfigure(block: () -> Unit) {
        gate.set(false)
        try {
            Thread.sleep(60)
        } catch (_: InterruptedException) {
        }
        try {
            block()
        } finally {
            gate.set(true)
        }
    }

    companion object {
        private const val TAG = "RAV-Camera"

        /** 66 ms -> ~15 Hz ring writes; 45 entries cover 3.0 s. */
        const val RING_PERIOD_NS = 66_000_000L
    }
}

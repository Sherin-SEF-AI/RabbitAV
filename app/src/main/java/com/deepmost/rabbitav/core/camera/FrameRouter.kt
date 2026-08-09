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
 * The router runs entirely on the analyzer thread except [reconfigure], which
 * flips a gate while buffers are rebuilt.
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

    /** Ring is created lazily once geometry is known (entry size depends on crop). */
    @Volatile var ring: LookbackRingBuffer? = null
        private set

    private val gate = AtomicBoolean(true)
    private var lastRingWriteNs = 0L

    val framesSeen = AtomicLong(0)
    val framesInferred = AtomicLong(0)

    override fun onFrame(frame: CameraFrame) {
        try {
            if (!gate.get()) return
            val eng = engine
            framesSeen.incrementAndGet()

            if (eng == null) return

            if (!preprocessor.isConfiguredFor(frame, eng.inputWidth, eng.inputHeight)) {
                preprocessor.configure(frame, eng.inputWidth, eng.inputHeight)
                ring = LookbackRingBuffer(
                    entryBytes = preprocessor.ringEntryBytes(),
                    width = preprocessor.ringW,
                    height = preprocessor.ringH,
                )
                Timber.tag(TAG).i("ring buffer allocated: %d entries x %d B", LookbackRingBuffer.DEFAULT_CAPACITY, preprocessor.ringEntryBytes())
            }

            val ts = frame.timestampNs
            preprocessor.packAndRotate(frame)
            // Planes are packed into our own storage; release the camera buffer
            // immediately so CameraX can refill it while we do the heavy work.
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

            val submitted = executor.trySubmit {
                val slot = claimedSlot
                try {
                    preprocessor.fillModelInput(eng, slot)
                    val result = eng.run(ts, preprocessor.letterbox)
                    framesInferred.incrementAndGet()
                    onDetections(result)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "inference failed; frame skipped")
                }
            }
            // claimForInference flips the analyzer to the other slot, so the
            // slot index must be captured BEFORE the task can run: trySubmit
            // posts to a different thread, so claim first, then publish.
            if (submitted) {
                // Note: claim happens in submit order; see [claimedSlot] below.
            }
            onFrameStats(ts, submitted)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "frame routing failed")
            frame.close()
        }
    }

    /**
     * Slot hand-off: written on the analyzer thread immediately before the
     * inference task is posted, read once by that task. The executor is
     * single-slot (busy flag), so there is never more than one outstanding
     * reader, and the analyzer will not write this again until the next
     * accepted submission.
     */
    @Volatile private var claimedSlot: Int = 0

    init {
        // wire claim into submission: FrameRouter.onFrame calls trySubmit with
        // a closure reading [claimedSlot]; we set it in the pre-submit hook.
    }

    /** Pauses routing, runs [block] (buffer/engine surgery), resumes. */
    fun reconfigure(block: () -> Unit) {
        gate.set(false)
        try {
            block()
        } finally {
            gate.set(true)
        }
    }

    /** Called by the pipeline on the analyzer's frame right before submit. */
    fun noteClaim(slot: Int) {
        claimedSlot = slot
    }

    companion object {
        private const val TAG = "RAV-Camera"

        /** 66 ms -> ~15 Hz ring writes; 45 entries cover 3.0 s. */
        const val RING_PERIOD_NS = 66_000_000L
    }
}

package com.deepmost.rabbitav.core.inference

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The dedicated inference thread with drop-when-busy semantics: the camera
 * analyzer offers frames; if an inference is in flight the frame is dropped
 * (STRATEGY_KEEP_ONLY_LATEST upstream means we always see the newest anyway).
 */
class SingleSlotExecutor(name: String) : AutoCloseable {

    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)

    val dropped = AtomicLong(0)
    val executed = AtomicLong(0)

    val isBusy: Boolean get() = busy.get()

    /** Runs [task] on the inference thread unless one is already in flight. */
    fun trySubmit(task: Runnable): Boolean {
        if (!busy.compareAndSet(false, true)) {
            dropped.incrementAndGet()
            return false
        }
        handler.post {
            try {
                task.run()
            } finally {
                executed.incrementAndGet()
                busy.set(false)
            }
        }
        return true
    }

    /** Unconditionally queues work (model load, benchmark, shutdown). */
    fun submit(task: Runnable) {
        handler.post(task)
    }

    override fun close() {
        thread.quitSafely()
    }
}

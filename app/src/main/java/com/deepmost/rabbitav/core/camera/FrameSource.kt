package com.deepmost.rabbitav.core.camera

/** Receives frames on the source's analyzer thread; must not block. */
fun interface FrameConsumer {
    /** Implementations must call [CameraFrame.close] when done with the planes. */
    fun onFrame(frame: CameraFrame)
}

/** A push source of YUV frames: live CameraX or MP4 replay. */
interface FrameSource {
    /** Begins delivering frames. Idempotent stop() must be safe afterwards. */
    suspend fun start(consumer: FrameConsumer)
    suspend fun stop()
}

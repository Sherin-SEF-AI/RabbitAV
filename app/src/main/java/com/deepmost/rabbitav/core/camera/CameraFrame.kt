package com.deepmost.rabbitav.core.camera

import java.nio.ByteBuffer

/**
 * Uniform view over a YUV_420_888 frame from either CameraX (ImageProxy) or
 * MediaCodec video replay (android.media.Image). Plane buffers are only valid
 * until [close]; the pipeline packs them into its own I420 storage immediately.
 */
interface CameraFrame {
    val width: Int
    val height: Int
    /** Clockwise rotation needed to make the image upright. */
    val rotationDegrees: Int
    /** Sensor timestamp (live) or mapped monotonic timestamp (replay), ns. */
    val timestampNs: Long

    fun planeBuffer(index: Int): ByteBuffer
    fun planeRowStride(index: Int): Int
    fun planePixelStride(index: Int): Int

    fun close()
}

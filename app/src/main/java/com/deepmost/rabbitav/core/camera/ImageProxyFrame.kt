package com.deepmost.rabbitav.core.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Zero-copy adapter over a CameraX ImageProxy. Reused per analyzer invocation
 * (single-threaded), so no per-frame allocation.
 */
class ImageProxyFrame : CameraFrame {

    private var proxy: ImageProxy? = null

    fun wrap(p: ImageProxy): ImageProxyFrame {
        proxy = p
        return this
    }

    override val width: Int get() = proxy?.width ?: 0
    override val height: Int get() = proxy?.height ?: 0
    override val rotationDegrees: Int get() = proxy?.imageInfo?.rotationDegrees ?: 0
    override val timestampNs: Long get() = proxy?.imageInfo?.timestamp ?: 0L

    override fun planeBuffer(index: Int): ByteBuffer = proxy!!.planes[index].buffer
    override fun planeRowStride(index: Int): Int = proxy!!.planes[index].rowStride
    override fun planePixelStride(index: Int): Int = proxy!!.planes[index].pixelStride

    override fun close() {
        proxy?.close()
        proxy = null
    }
}

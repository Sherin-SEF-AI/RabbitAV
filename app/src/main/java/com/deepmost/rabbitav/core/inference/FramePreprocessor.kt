package com.deepmost.rabbitav.core.inference

import com.deepmost.rabbitav.core.camera.CameraFrame
import io.github.crow_misia.libyuv.AbgrBuffer
import io.github.crow_misia.libyuv.FilterMode
import io.github.crow_misia.libyuv.I420Buffer
import io.github.crow_misia.libyuv.RotateMode
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * All YUV plumbing between a camera frame and (a) the model input buffer and
 * (b) the lookback ring crop. Everything is preallocated in [configure]; the
 * per-frame path allocates nothing. libyuv (NEON) does rotate/scale/convert;
 * the only Kotlin loops are stride-aware plane packing and the final LUT write.
 *
 * Threading contract: [packAndRotate] and [writeRingCrop] run on the camera
 * analyzer thread. The upright result lives in a two-slot buffer; when the
 * inference thread is free, the analyzer calls [claimForInference] and hands
 * the returned slot to the inference thread, which reads it via
 * [fillModelInput]. The analyzer keeps writing into the OTHER slot, so a slow
 * inference never races a fresh frame.
 */
class FramePreprocessor : AutoCloseable {

    // Source geometry
    private var srcW = 0
    private var srcH = 0
    private var rotation = 0

    // Upright geometry (after rotation)
    var uprightW = 0; private set
    var uprightH = 0; private set

    /** Pack target when rotation != 0 (pre-rotation layout). */
    private var packed: I420Buffer? = null

    /** Double-buffered upright frames. */
    private val upright = arrayOfNulls<I420Buffer>(2)
    private var writeIdx = 0

    // Model-input stage (inference thread only)
    private var contentI420: I420Buffer? = null
    private var contentAbgr: AbgrBuffer? = null
    val letterbox = LetterboxMeta()

    // Lookback crop stage (analyzer thread only)
    private var cropX = 0
    private var cropY = 0
    private var cropW = 0
    private var cropH = 0
    var ringW = 0; private set
    var ringH = 0; private set
    private var cropI420: I420Buffer? = null
    private var cropScaled: I420Buffer? = null
    private var cropAbgr: AbgrBuffer? = null

    private var configured = false
    private var modelW = 0
    private var modelH = 0

    fun isConfiguredFor(frame: CameraFrame, modelW: Int, modelH: Int): Boolean =
        configured && frame.width == srcW && frame.height == srcH &&
            frame.rotationDegrees == rotation && modelW == this.modelW && modelH == this.modelH

    fun configure(frame: CameraFrame, modelW: Int, modelH: Int) {
        close()
        srcW = frame.width
        srcH = frame.height
        rotation = ((frame.rotationDegrees % 360) + 360) % 360
        this.modelW = modelW
        this.modelH = modelH
        val swap = rotation == 90 || rotation == 270
        uprightW = if (swap) srcH else srcW
        uprightH = if (swap) srcW else srcH

        if (rotation != 0) packed = I420Buffer.allocate(srcW, srcH)
        upright[0] = I420Buffer.allocate(uprightW, uprightH)
        upright[1] = I420Buffer.allocate(uprightW, uprightH)
        writeIdx = 0

        letterbox.configure(uprightW, uprightH, modelW, modelH)
        contentI420 = I420Buffer.allocate(letterbox.contentW, letterbox.contentH)
        contentAbgr = AbgrBuffer.allocate(letterbox.contentW, letterbox.contentH)

        // Even-aligned crop region (I420 chroma is 2x2 subsampled)
        cropW = ((uprightW * CROP_WIDTH_FRACTION).toInt()) and 0x7FFFFFFE
        cropH = ((uprightH * CROP_HEIGHT_FRACTION).toInt()) and 0x7FFFFFFE
        cropX = ((uprightW - cropW) / 2) and 0x7FFFFFFE
        cropY = (uprightH - cropH) and 0x7FFFFFFE
        ringW = RING_WIDTH
        ringH = ((cropH * RING_WIDTH) / cropW) and 0x7FFFFFFE
        cropI420 = I420Buffer.allocate(cropW, cropH)
        cropScaled = I420Buffer.allocate(ringW, ringH)
        cropAbgr = AbgrBuffer.allocate(ringW, ringH)

        configured = true
        Timber.tag(TAG).i(
            "preprocessor configured: src=%dx%d rot=%d upright=%dx%d model=%dx%d content=%dx%d ring=%dx%d",
            srcW, srcH, rotation, uprightW, uprightH, modelW, modelH,
            letterbox.contentW, letterbox.contentH, ringW, ringH
        )
    }

    /** Ring entry byte size for the current geometry (RGB888). */
    fun ringEntryBytes(): Int = ringW * ringH * 3

    /**
     * Copies the camera planes into contiguous I420 storage (stride-aware),
     * rotating upright into the current write slot. Analyzer thread.
     */
    fun packAndRotate(frame: CameraFrame) {
        val target = upright[writeIdx] ?: return
        if (rotation == 0) {
            packInto(frame, target)
        } else {
            val p = packed ?: return
            packInto(frame, p)
            val mode = when (rotation) {
                90 -> RotateMode.ROTATE_90
                180 -> RotateMode.ROTATE_180
                else -> RotateMode.ROTATE_270
            }
            p.rotate(target, mode)
        }
    }

    private fun packInto(frame: CameraFrame, dst: I420Buffer) {
        packPlane(frame, 0, dst.planeY.buffer, dst.planeY.rowStride.value, srcW, srcH)
        packPlane(frame, 1, dst.planeU.buffer, dst.planeU.rowStride.value, srcW / 2, srcH / 2)
        packPlane(frame, 2, dst.planeV.buffer, dst.planeV.rowStride.value, srcW / 2, srcH / 2)
    }

    private val rowScratch = ByteArray(4096)

    private fun packPlane(
        frame: CameraFrame, plane: Int, dst: ByteBuffer, dstRowStride: Int, w: Int, h: Int,
    ) {
        val src = frame.planeBuffer(plane)
        val srcRowStride = frame.planeRowStride(plane)
        val pixStride = frame.planePixelStride(plane)
        src.rewind()
        dst.rewind()
        if (pixStride == 1) {
            if (srcRowStride == w && dstRowStride == w) {
                val n = w * h
                val oldLimit = src.limit()
                src.limit(minOf(n, src.capacity()))
                dst.put(src)
                src.limit(oldLimit)
            } else {
                for (y in 0 until h) {
                    src.position(y * srcRowStride)
                    src.get(rowScratch, 0, w)
                    dst.position(y * dstRowStride)
                    dst.put(rowScratch, 0, w)
                }
            }
        } else {
            // interleaved chroma (NV12/NV21-style planes surfaced via 420_888)
            for (y in 0 until h) {
                val srcBase = y * srcRowStride
                val dstBase = y * dstRowStride
                for (x in 0 until w) {
                    dst.put(dstBase + x, src.get(srcBase + x * pixStride))
                }
            }
        }
        src.rewind()
        dst.rewind()
    }

    /**
     * Hands the freshly written upright slot to the inference thread and flips
     * the analyzer to the other slot. Call ONLY when the inference executor
     * accepted the frame. Analyzer thread.
     */
    fun claimForInference(): Int {
        val claimed = writeIdx
        writeIdx = 1 - writeIdx
        return claimed
    }

    /**
     * Scales+converts the claimed upright slot into the engine's input buffer
     * through the quantization LUT. Inference thread.
     */
    fun fillModelInput(engine: InferenceEngine, slot: Int) {
        val src = upright[slot] ?: return
        val content = contentI420 ?: return
        val abgr = contentAbgr ?: return
        src.scale(content, FilterMode.BILINEAR)
        content.convertTo(abgr)
        writeLetterboxContent(engine, abgr.asBuffer())
    }

    private fun writeLetterboxContent(engine: InferenceEngine, abgrBytes: ByteBuffer) {
        abgrBytes.rewind()
        val input = engine.inputBuffer
        val lb = letterbox
        val cw = lb.contentW
        val ch = lb.contentH
        if (engine.isFloatInput) {
            val lut = engine.floatLut
            val f = input.asFloatBuffer()
            for (y in 0 until ch) {
                var srcIdx = y * cw * 4
                var dstIdx = ((y + lb.padY) * lb.dstW + lb.padX) * 3
                for (x in 0 until cw) {
                    // ABGR byte order in memory: R,G,B,A
                    f.put(dstIdx, lut[abgrBytes.get(srcIdx).toInt() and 0xFF])
                    f.put(dstIdx + 1, lut[abgrBytes.get(srcIdx + 1).toInt() and 0xFF])
                    f.put(dstIdx + 2, lut[abgrBytes.get(srcIdx + 2).toInt() and 0xFF])
                    srcIdx += 4
                    dstIdx += 3
                }
            }
        } else {
            val lut = engine.quantLut
            for (y in 0 until ch) {
                var srcIdx = y * cw * 4
                var dstIdx = ((y + lb.padY) * lb.dstW + lb.padX) * 3
                for (x in 0 until cw) {
                    input.put(dstIdx, lut[abgrBytes.get(srcIdx).toInt() and 0xFF])
                    input.put(dstIdx + 1, lut[abgrBytes.get(srcIdx + 1).toInt() and 0xFF])
                    input.put(dstIdx + 2, lut[abgrBytes.get(srcIdx + 2).toInt() and 0xFF])
                    srcIdx += 4
                    dstIdx += 3
                }
            }
        }
    }

    /**
     * Produces the lookback ring crop (RGB888) from the just-written upright
     * slot into [dst]. Analyzer thread, immediately after [packAndRotate]
     * (before any claim), so the slot is still analyzer-owned.
     */
    fun writeRingCrop(dst: ByteArray) {
        val up = upright[writeIdx] ?: return
        val cIn = cropI420 ?: return
        val cScaled = cropScaled ?: return
        val cAbgr = cropAbgr ?: return

        copySubPlane(up.planeY.buffer, up.planeY.rowStride.value, cropX, cropY, cIn.planeY.buffer, cIn.planeY.rowStride.value, cropW, cropH)
        copySubPlane(up.planeU.buffer, up.planeU.rowStride.value, cropX / 2, cropY / 2, cIn.planeU.buffer, cIn.planeU.rowStride.value, cropW / 2, cropH / 2)
        copySubPlane(up.planeV.buffer, up.planeV.rowStride.value, cropX / 2, cropY / 2, cIn.planeV.buffer, cIn.planeV.rowStride.value, cropW / 2, cropH / 2)

        cIn.scale(cScaled, FilterMode.BILINEAR)
        cScaled.convertTo(cAbgr)

        val src = cAbgr.asBuffer()
        src.rewind()
        var si = 0
        var di = 0
        val n = ringW * ringH
        for (i in 0 until n) {
            dst[di] = src.get(si)         // R
            dst[di + 1] = src.get(si + 1) // G
            dst[di + 2] = src.get(si + 2) // B
            si += 4
            di += 3
        }
    }

    private fun copySubPlane(
        src: ByteBuffer, srcStride: Int, x0: Int, y0: Int,
        dst: ByteBuffer, dstStride: Int, w: Int, h: Int,
    ) {
        for (y in 0 until h) {
            src.position((y0 + y) * srcStride + x0)
            src.get(rowScratch, 0, w)
            dst.position(y * dstStride)
            dst.put(rowScratch, 0, w)
        }
        src.rewind()
        dst.rewind()
    }

    override fun close() {
        packed?.close(); packed = null
        upright[0]?.close(); upright[0] = null
        upright[1]?.close(); upright[1] = null
        contentI420?.close(); contentI420 = null
        contentAbgr?.close(); contentAbgr = null
        cropI420?.close(); cropI420 = null
        cropScaled?.close(); cropScaled = null
        cropAbgr?.close(); cropAbgr = null
        configured = false
    }

    companion object {
        private const val TAG = "RAV-Camera"

        /** Lookback crop geometry (Section 5.1): bottom 55%, central 70%. */
        const val CROP_HEIGHT_FRACTION = 0.55f
        const val CROP_WIDTH_FRACTION = 0.70f
        const val RING_WIDTH = 320
    }
}

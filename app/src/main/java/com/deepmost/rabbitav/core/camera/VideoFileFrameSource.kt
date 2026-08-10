package com.deepmost.rabbitav.core.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Section 5.12 video replay: decodes an MP4 through MediaCodec into flexible
 * YUV_420_888 Images and pushes them down the EXACT same FrameRouter path as
 * live camera, paced at the native video rate. Timestamps are mapped onto the
 * monotonic elapsedRealtimeNanos clock and stay monotonic across loops.
 */
class VideoFileFrameSource(
    private val file: File,
    private val loop: Boolean,
) : FrameSource {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    override suspend fun start(consumer: FrameConsumer) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ decodeLoop(consumer) }, "rav-replay").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    override suspend fun stop() {
        running.set(false)
        thread?.join(3000)
        thread = null
    }

    private fun decodeLoop(consumer: FrameConsumer) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Timber.tag(TAG).e("no video track in %s", file)
                return
            }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            Timber.tag(TAG).i(
                "replay started: %s %dx%d rot=%d loop=%b",
                file.name,
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT),
                rotation, loop
            )

            val info = MediaCodec.BufferInfo()
            val baseElapsedNs = SystemClock.elapsedRealtimeNanos()
            var basePtsUs = -1L
            var loopOffsetUs = 0L
            var lastPtsUs = 0L
            var inputDone = false
            var lastStampNs = 0L
            val frameAdapter = MediaImageFrame()

            while (running.get()) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            if (loop) {
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                loopOffsetUs += lastPtsUs + 33_333L
                                // queue nothing this round; next dequeue reads from t=0
                                codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            } else {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            }
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            codec.releaseOutputBuffer(outIdx, false)
                            Timber.tag(TAG).i("replay reached EOS")
                            return
                        }
                        if (info.size == 0) {
                            codec.releaseOutputBuffer(outIdx, false)
                        } else {
                            val ptsUs = info.presentationTimeUs + loopOffsetUs
                            lastPtsUs = info.presentationTimeUs
                            if (basePtsUs < 0) basePtsUs = ptsUs

                            // Pace to native rate against the monotonic clock.
                            val dueNs = baseElapsedNs + (ptsUs - basePtsUs) * 1000L
                            val waitMs = (dueNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                            if (waitMs > 1) {
                                try {
                                    Thread.sleep(waitMs.coerceAtMost(500))
                                } catch (_: InterruptedException) {
                                    return
                                }
                            }

                            // Frame timestamps must be MONOTONIC DELIVERY TIME,
                            // not media pts: when decode lags (thermal
                            // mitigation) the input side wraps the loop while
                            // outputs are mid-clip, and pts bookkeeping would
                            // jump backward — starving the FPS cap and skewing
                            // tracker dt (found on-device). Delivery time equals
                            // media time whenever pacing keeps up.
                            val stampNs = maxOf(
                                maxOf(dueNs, SystemClock.elapsedRealtimeNanos()),
                                lastStampNs + 1_000_000L
                            )
                            lastStampNs = stampNs

                            val image = codec.getOutputImage(outIdx)
                            if (image != null) {
                                frameAdapter.wrap(image, rotation, stampNs) {
                                    codec.releaseOutputBuffer(outIdx, false)
                                }
                                consumer.onFrame(frameAdapter)
                            } else {
                                codec.releaseOutputBuffer(outIdx, false)
                            }
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Timber.tag(TAG).d("decoder format: %s", codec.outputFormat)
                    else -> Unit // TRY_AGAIN_LATER
                }
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "replay decode failed")
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "codec release failed")
            }
            extractor?.release()
            Timber.tag(TAG).i("replay stopped")
        }
    }

    /** Adapter over MediaCodec's flexible-YUV Image. */
    private class MediaImageFrame : CameraFrame {
        private var image: Image? = null
        private var rotation = 0
        private var tsNs = 0L
        private var onClose: (() -> Unit)? = null

        fun wrap(img: Image, rotationDegrees: Int, timestampNs: Long, release: () -> Unit) {
            image = img
            rotation = rotationDegrees
            tsNs = timestampNs
            onClose = release
        }

        override val width: Int get() = image?.width ?: 0
        override val height: Int get() = image?.height ?: 0
        override val rotationDegrees: Int get() = rotation
        override val timestampNs: Long get() = tsNs

        override fun planeBuffer(index: Int): ByteBuffer = image!!.planes[index].buffer
        override fun planeRowStride(index: Int): Int = image!!.planes[index].rowStride
        override fun planePixelStride(index: Int): Int = image!!.planes[index].pixelStride

        override fun close() {
            try {
                image?.close()
            } catch (_: Throwable) {
            }
            image = null
            onClose?.invoke()
            onClose = null
        }
    }

    companion object {
        private const val TAG = "RAV-Camera"
    }
}

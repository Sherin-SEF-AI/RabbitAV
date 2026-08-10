package com.deepmost.rabbitav.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.deepmost.rabbitav.core.camera.ChunkRing
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Section 5.11 incident clips (opt-in, default off): keeps a rolling ~10 s
 * AVC-encoded buffer of the upright analysis frames and, on FCW-CRITICAL,
 * persists pre (10 s ring) + post (15 s) to app-private storage
 * (filesDir/incidents), auto-pruned to 2 GB.
 *
 * Encodes at the ANALYSIS resolution (640x480-class) rather than the spec's
 * nominal 720p: a second high-res camera stream would tax the floor device;
 * the analysis stream is what the detector actually saw, which is the more
 * truthful incident record (noted in DECISIONS.md).
 *
 * Frames enter via [offerFrame] as packed I420 byte arrays at <= [FPS] on the
 * analyzer thread; all encoding runs on the dedicated "rav-clip" thread.
 */
class IncidentClipRecorder(private val context: Context) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var outputFormat: MediaFormat? = null
    private var width = 0
    private var height = 0

    private val ring = ChunkRing(PRE_TRIGGER_US)
    private val running = AtomicBoolean(false)

    // small pool of frame buffers handed between analyzer and encoder thread
    private val pool = ArrayDeque<ByteArray>()
    private val poolLock = Any()

    @Volatile private var postTriggerDeadlineUs = 0L
    @Volatile private var triggerReason: String = ""
    private var basePtsUs = -1L

    val isRunning: Boolean get() = running.get()

    fun clipsDir(): File = File(context.filesDir, "incidents").apply { mkdirs() }

    fun start(frameWidth: Int, frameHeight: Int) {
        if (!running.compareAndSet(false, true)) return
        width = frameWidth and 0x7FFFFFFE
        height = frameHeight and 0x7FFFFFFE
        val t = HandlerThread("rav-clip").apply { start() }
        thread = t
        handler = Handler(t.looper)
        handler?.post {
            try {
                initCodec()
                prune()
                Timber.tag(TAG).i("incident recorder started %dx%d @%d fps", width, height, FPS)
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "incident recorder init failed; disabled for this session")
                running.set(false)
            }
        }
    }

    private fun initCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            // 1 s GOPs keep ring trimming and clip starts keyframe-aligned
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        basePtsUs = -1L
        synchronized(poolLock) {
            pool.clear()
            repeat(POOL_SIZE) { pool.add(ByteArray(width * height * 3 / 2)) }
        }
    }

    /**
     * Analyzer thread: hands over one packed I420 frame if a pool buffer is
     * free (drops silently otherwise — the encoder sets the pace).
     * @return a buffer the caller must fill BEFORE calling [submitFrame], or
     * null when the recorder is saturated/stopped.
     */
    fun borrowBuffer(): ByteArray? {
        if (!running.get()) return null
        synchronized(poolLock) {
            return pool.removeFirstOrNull()
        }
    }

    fun submitFrame(frame: ByteArray, timestampNs: Long) {
        val h = handler
        if (!running.get() || h == null) {
            recycle(frame)
            return
        }
        h.post { encode(frame, timestampNs / 1000) }
    }

    private fun recycle(frame: ByteArray) {
        synchronized(poolLock) {
            if (pool.size < POOL_SIZE) pool.addLast(frame)
        }
    }

    /** Returns a borrowed buffer unused (e.g., the frame copy failed). */
    fun returnBuffer(frame: ByteArray) = recycle(frame)

    private fun encode(frame: ByteArray, ptsUsRaw: Long) {
        val c = codec ?: run { recycle(frame); return }
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                if (basePtsUs < 0) basePtsUs = ptsUsRaw
                val ptsUs = ptsUsRaw - basePtsUs
                val image = c.getInputImage(inIdx)
                if (image != null) {
                    copyI420ToImage(frame, image)
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                } else {
                    val buf = c.getInputBuffer(inIdx)!!
                    buf.clear()
                    buf.put(frame, 0, minOf(frame.size, buf.capacity()))
                    c.queueInputBuffer(inIdx, 0, minOf(frame.size, buf.capacity()), ptsUs, 0)
                }
            }
            drainEncoder(c)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "encode failed; frame skipped")
        } finally {
            recycle(frame)
        }
    }

    private fun copyI420ToImage(src: ByteArray, image: android.media.Image) {
        val w = width
        val h = height
        // planes: Y then U then V, packed
        var srcOff = 0
        val yPlane = image.planes[0]
        copyPlane(src, srcOff, w, h, yPlane.buffer, yPlane.rowStride, yPlane.pixelStride)
        srcOff += w * h
        val uPlane = image.planes[1]
        copyPlane(src, srcOff, w / 2, h / 2, uPlane.buffer, uPlane.rowStride, uPlane.pixelStride)
        srcOff += w * h / 4
        val vPlane = image.planes[2]
        copyPlane(src, srcOff, w / 2, h / 2, vPlane.buffer, vPlane.rowStride, vPlane.pixelStride)
    }

    private fun copyPlane(
        src: ByteArray, srcOff: Int, w: Int, h: Int,
        dst: ByteBuffer, rowStride: Int, pixelStride: Int,
    ) {
        if (pixelStride == 1) {
            for (row in 0 until h) {
                dst.position(row * rowStride)
                dst.put(src, srcOff + row * w, w)
            }
        } else {
            for (row in 0 until h) {
                var d = row * rowStride
                var s = srcOff + row * w
                for (col in 0 until w) {
                    dst.put(d, src[s])
                    d += pixelStride
                    s++
                }
            }
        }
    }

    private val bufferInfo = MediaCodec.BufferInfo()

    private fun drainEncoder(c: MediaCodec) {
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = c.outputFormat
                    Timber.tag(TAG).d("encoder format: %s", outputFormat)
                }
                outIdx >= 0 -> {
                    if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = c.getOutputBuffer(outIdx)!!
                        val data = ByteArray(bufferInfo.size)
                        buf.position(bufferInfo.offset)
                        buf.get(data)
                        ring.append(
                            ChunkRing.Chunk(
                                data,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                            )
                        )
                        maybeFinishClip(bufferInfo.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
                else -> return
            }
        }
    }

    /** Alert path: capture pre (ring) + [POST_TRIGGER_US] more, then save. */
    fun trigger(reason: String) {
        if (!running.get()) return
        if (postTriggerDeadlineUs > 0) return // already capturing an incident
        triggerReason = reason
        handler?.post {
            // deadline in encoder pts-time: newest pts + post window
            val newest = lastPtsUs()
            postTriggerDeadlineUs = newest + POST_TRIGGER_US
            Timber.tag(TAG).i("incident trigger (%s): saving after %d s tail", reason, POST_TRIGGER_US / 1_000_000)
        }
    }

    private fun lastPtsUs(): Long = ring.snapshot().lastOrNull()?.ptsUs ?: 0L

    private fun maybeFinishClip(currentPtsUs: Long) {
        val deadline = postTriggerDeadlineUs
        if (deadline <= 0 || currentPtsUs < deadline) return
        postTriggerDeadlineUs = 0
        saveClip()
    }

    private fun saveClip() {
        val format = outputFormat ?: run {
            Timber.tag(TAG).w("no encoder format; clip dropped")
            return
        }
        val chunks = ring.snapshot()
        if (chunks.isEmpty()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(clipsDir(), "incident_${stamp}_$triggerReason.mp4")
        try {
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            val pts0 = chunks.first().ptsUs
            for (c in chunks) {
                info.set(
                    0, c.data.size, c.ptsUs - pts0,
                    if (c.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
                muxer.writeSampleData(track, ByteBuffer.wrap(c.data), info)
            }
            muxer.stop()
            muxer.release()
            Timber.tag(TAG).i(
                "incident clip saved: %s (%.1f MB, %.1f s)",
                file.name, file.length() / 1048576.0,
                (chunks.last().ptsUs - pts0) / 1e6
            )
            prune()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "clip mux failed")
            file.delete()
        }
    }

    /** Keeps the incidents dir under 2 GB, oldest-first deletion (Section 5.11). */
    fun prune() {
        val files = clipsDir().listFiles { f -> f.extension == "mp4" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            Timber.tag(TAG).i("pruning old incident clip %s", f.name)
            f.delete()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val h = handler
        val done = java.util.concurrent.CountDownLatch(1)
        h?.post {
            try {
                codec?.stop()
                codec?.release()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "encoder release failed")
            }
            codec = null
            ring.clear()
            postTriggerDeadlineUs = 0
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
        Timber.tag(TAG).i("incident recorder stopped")
    }

    companion object {
        private const val TAG = "RAV-Clip"

        /** Encode cadence; 10 fps keeps floor-device overhead negligible. */
        const val FPS = 10
        const val BIT_RATE = 2_000_000

        /** Pre-trigger ring (Section 5.11): 10 s. */
        const val PRE_TRIGGER_US = 10_000_000L

        /** Post-trigger tail (Section 5.11): 15 s. */
        const val POST_TRIGGER_US = 15_000_000L

        /** Storage cap (Section 5.11): 2 GB. */
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        const val POOL_SIZE = 3
    }
}

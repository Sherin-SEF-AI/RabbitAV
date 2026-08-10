package com.deepmost.rabbitav.core.camera

import java.util.ArrayDeque

/**
 * Time-windowed ring of encoded video chunks for the incident recorder
 * (Section 5.11). Pure logic, unit-tested: keeps at least [windowUs] of
 * history, trimming ONLY at keyframe boundaries so any snapshot starts
 * decodable.
 */
class ChunkRing(private val windowUs: Long) {

    class Chunk(
        val data: ByteArray,
        val ptsUs: Long,
        val isKeyframe: Boolean,
    )

    private val chunks = ArrayDeque<Chunk>()
    var totalBytes: Long = 0
        private set

    val size: Int get() = chunks.size

    @Synchronized
    fun append(chunk: Chunk) {
        chunks.addLast(chunk)
        totalBytes += chunk.data.size
        trim()
    }

    private fun trim() {
        val newest = chunks.peekLast()?.ptsUs ?: return
        val cutoff = newest - windowUs
        // Drop from the head, but never leave the ring starting on a
        // non-keyframe: advance only when the NEXT chunk region still covers
        // the window from a keyframe.
        while (chunks.size > 1) {
            val head = chunks.peekFirst()!!
            if (head.ptsUs >= cutoff) break
            // find the next keyframe after head
            val it = chunks.iterator()
            it.next() // skip head
            var nextKey: Chunk? = null
            while (it.hasNext()) {
                val c = it.next()
                if (c.isKeyframe) {
                    nextKey = c
                    break
                }
            }
            // only drop up to (not including) a keyframe that still precedes
            // or equals the cutoff coverage requirement
            if (nextKey == null || nextKey.ptsUs > cutoff) break
            while (chunks.peekFirst() !== nextKey) {
                totalBytes -= chunks.pollFirst()!!.data.size
            }
        }
    }

    /** Ordered copy for muxing, starting at the first keyframe. */
    @Synchronized
    fun snapshot(): List<Chunk> {
        val out = ArrayList<Chunk>(chunks.size)
        var started = false
        for (c in chunks) {
            if (!started && !c.isKeyframe) continue
            started = true
            out.add(c)
        }
        return out
    }

    @Synchronized
    fun clear() {
        chunks.clear()
        totalBytes = 0
    }
}

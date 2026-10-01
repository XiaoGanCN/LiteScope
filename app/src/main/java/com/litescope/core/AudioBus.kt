package com.litescope.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe ring buffer holding the most recent seconds of captured playback audio as
 * interleaved stereo floats.
 *
 * The capture thread is the only writer; the analysis thread and every scope view read from it.
 * Readers address data by *absolute frame index* (frames written since the last [configure]),
 * which lets each consumer keep its own cursor and consume at its own pace.
 */
class AudioBus {

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private var ring = FloatArray(0)
    private var capacityFrames = 0
    private var writePos = 0
    private var framesWritten = 0L

    @Volatile
    var sampleRate: Int = 48000
        private set

    /** Always 2: mono captures are duplicated into both channels. */
    @Volatile
    var channelCount: Int = 2
        private set

    @Volatile
    var stopped: Boolean = false
        private set

    /** (Re)allocates the ring for [sampleRate] and [historySeconds] of stereo audio. */
    fun configure(sampleRate: Int, historySeconds: Float) {
        lock.withLock {
            this.sampleRate = sampleRate
            this.channelCount = 2
            capacityFrames = (sampleRate * historySeconds).toInt().coerceAtLeast(sampleRate)
            ring = FloatArray(capacityFrames * 2)
            writePos = 0
            framesWritten = 0L
            stopped = false
            cond.signalAll()
        }
    }

    val capacity: Int get() = lock.withLock { capacityFrames }

    val totalFrames: Long get() = lock.withLock { framesWritten }

    /** Oldest frame index still present in the ring. */
    val oldestFrame: Long get() = lock.withLock { (framesWritten - capacityFrames).coerceAtLeast(0L) }

    fun stop() {
        lock.withLock {
            stopped = true
            cond.signalAll()
        }
    }

    /**
     * Appends [frames] frames of [src]. [srcChannels] may be 1 (mono, duplicated) or 2.
     * Extra frames beyond the ring capacity are dropped from the front, as expected for a
     * rolling history.
     */
    fun write(src: FloatArray, frames: Int, srcChannels: Int = 2) {
        if (frames <= 0) return
        lock.withLock {
            if (capacityFrames == 0) return
            var wp = writePos
            var i = 0
            var f = 0
            while (f < frames) {
                val l: Float
                val r: Float
                if (srcChannels >= 2) {
                    l = src[i]
                    r = src[i + 1]
                    i += 2
                } else {
                    l = src[i]
                    r = src[i]
                    i += 1
                }
                ring[wp * 2] = l
                ring[wp * 2 + 1] = r
                wp++
                if (wp >= capacityFrames) wp = 0
                f++
            }
            writePos = wp
            framesWritten += frames
            cond.signalAll()
        }
    }

    /**
     * Copies stereo frames `[startFrame, startFrame + frames)` into [dest] (interleaved).
     * Returns the number of frames actually available and copied.
     */
    fun copyStereo(dest: FloatArray, startFrame: Long, frames: Int): Int {
        if (frames <= 0) return 0
        lock.withLock {
            if (capacityFrames == 0) return 0
            val oldest = (framesWritten - capacityFrames).coerceAtLeast(0L)
            var start = startFrame
            var count = frames
            if (start < oldest) {
                count -= (oldest - start).toInt()
                start = oldest
            }
            if (start + count > framesWritten) count = (framesWritten - start).toInt()
            if (count <= 0) return 0
            var srcFrame = (start % capacityFrames).toInt()
            var dst = 0
            var remaining = count
            while (remaining > 0) {
                val chunk = minOf(remaining, capacityFrames - srcFrame)
                System.arraycopy(ring, srcFrame * 2, dest, dst * 2, chunk * 2)
                dst += chunk
                remaining -= chunk
                srcFrame = 0
            }
            return count
        }
    }

    /** Copies the most recent [frames] stereo frames into [dest]. */
    fun copyLatestStereo(dest: FloatArray, frames: Int): Int {
        if (frames <= 0) return 0
        lock.withLock {
            if (capacityFrames == 0) return 0
            val available = minOf(frames.toLong(), framesWritten, capacityFrames.toLong()).toInt()
            if (available <= 0) return 0
            val start = framesWritten - available
            var srcFrame = (start % capacityFrames).toInt()
            var dst = 0
            var remaining = available
            while (remaining > 0) {
                val chunk = minOf(remaining, capacityFrames - srcFrame)
                System.arraycopy(ring, srcFrame * 2, dest, dst * 2, chunk * 2)
                dst += chunk
                remaining -= chunk
                srcFrame = 0
            }
            return available
        }
    }

    /**
     * Computes a min/max envelope for the wave and vector scopes in a single pass over the ring,
     * so a 30 fps redraw never copies more than it draws.
     *
     * Writes [COLUMNS_STRIDE] floats per column starting at [outOffset]:
     * `minL, maxL, minR, maxR, minSum, maxSum, minDiff, maxDiff` where
     * `sum = L + R` and `diff = L - R` (literal addition/subtraction, as used by the wave scope's
     * sum view).
     *
     * @return the number of columns with real data (rest are filled by repeating the neighbour)
     */
    fun stereoEnvelope(
        out: FloatArray,
        startFrame: Long,
        frames: Int,
        columns: Int,
        outOffset: Int = 0
    ): Int {
        if (columns <= 0 || frames <= 0) return 0
        lock.withLock {
            if (capacityFrames == 0) return 0
            val oldest = (framesWritten - capacityFrames).coerceAtLeast(0L)
            var start = startFrame
            var count = frames
            if (start < oldest) {
                count -= (oldest - start).toInt()
                start = oldest
            }
            if (start + count > framesWritten) count = (framesWritten - start).toInt()
            if (count <= 0) return 0

            var idx = (start % capacityFrames).toInt()
            var col = -1
            var minL = 0f; var maxL = 0f
            var minR = 0f; var maxR = 0f
            var minS = 0f; var maxS = 0f
            var minD = 0f; var maxD = 0f
            var written = -1

            fun flush(c: Int) {
                val base = outOffset + c * COLUMNS_STRIDE
                out[base] = minL; out[base + 1] = maxL
                out[base + 2] = minR; out[base + 3] = maxR
                out[base + 4] = minS; out[base + 5] = maxS
                out[base + 6] = minD; out[base + 7] = maxD
                written = c
            }

            fun fillGap(upTo: Int) {
                // Columns with no samples (very long time base) repeat the previous column.
                if (written < 0 || upTo <= written + 1) return
                val from = outOffset + written * COLUMNS_STRIDE
                for (c in written + 1 until upTo) {
                    val to = outOffset + c * COLUMNS_STRIDE
                    System.arraycopy(out, from, out, to, COLUMNS_STRIDE)
                }
                written = upTo - 1
            }

            for (f in 0 until count) {
                val c = ((f.toLong() * columns) / count).toInt().coerceIn(0, columns - 1)
                if (c != col) {
                    if (col >= 0) flush(col)
                    fillGap(c)
                    col = c
                    val l = ring[idx * 2]
                    val r = ring[idx * 2 + 1]
                    val s = l + r
                    val d = l - r
                    minL = l; maxL = l
                    minR = r; maxR = r
                    minS = s; maxS = s
                    minD = d; maxD = d
                } else {
                    val l = ring[idx * 2]
                    val r = ring[idx * 2 + 1]
                    val s = l + r
                    val d = l - r
                    if (l < minL) minL = l
                    if (l > maxL) maxL = l
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                    if (s < minS) minS = s
                    if (s > maxS) maxS = s
                    if (d < minD) minD = d
                    if (d > maxD) maxD = d
                }
                idx++
                if (idx >= capacityFrames) idx = 0
            }
            if (col >= 0) flush(col)
            fillGap(columns)
            return columns
        }
    }

    /** Blocks until `framesWritten - cursor >= needed`, or the timeout elapses. */    fun awaitFrames(cursor: Long, needed: Int, timeoutMs: Long): Boolean {
        lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!stopped && framesWritten - cursor < needed) {
                if (framesWritten - cursor >= needed) break
                val remain = deadline - System.nanoTime()
                if (remain <= 0) return false
                try {
                    cond.await(remain, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return !stopped && framesWritten - cursor >= needed
        }
    }

    companion object {
        /** Floats written per column by [stereoEnvelope]. */
        const val COLUMNS_STRIDE = 8
    }
}

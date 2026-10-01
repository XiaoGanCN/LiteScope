package com.litescope.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The ring buffer is the single point every scope reads from, so its cursor arithmetic, wrapping
 * and envelope are exercised directly here.
 */
class AudioBusTest {

    private fun newBus(rate: Int = 1000, historySeconds: Float = 1f): AudioBus =
        AudioBus().apply { configure(rate, historySeconds) }

    /** Fills [frames] frames with L = i / 1000, R = -i / 1000. */
    private fun AudioBus.writeRamp(frames: Int) {
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            buf[i * 2] = i / 1000f
            buf[i * 2 + 1] = -i / 1000f
        }
        write(buf, frames, 2)
    }

    @Test
    fun writesAndReadsBackStereoFrames() {
        val bus = newBus()
        bus.writeRamp(100)
        assertEquals(100L, bus.totalFrames)

        val out = FloatArray(20)
        val copied = bus.copyStereo(out, 40, 10)
        assertEquals(10, copied)
        assertEquals(40 / 1000f, out[0], 1e-6f)
        assertEquals(-40 / 1000f, out[1], 1e-6f)
        assertEquals(49 / 1000f, out[18], 1e-6f)
        assertEquals(-49 / 1000f, out[19], 1e-6f)
    }

    @Test
    fun monoCaptureIsDuplicatedIntoBothChannels() {
        val bus = newBus()
        val mono = floatArrayOf(0.25f, 0.5f)
        bus.write(mono, 2, 1)
        val out = FloatArray(4)
        assertEquals(2, bus.copyStereo(out, 0, 2))
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(0.25f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
        assertEquals(0.5f, out[3], 1e-6f)
    }

    @Test
    fun historyOlderThanTheRingIsDropped() {
        val bus = newBus(rate = 1000, historySeconds = 1f) // 1000 frame capacity
        bus.writeRamp(1500)
        assertEquals(1500L, bus.totalFrames)
        assertEquals(500L, bus.oldestFrame)

        val out = FloatArray(400)
        // A window that starts before the oldest retained frame is clamped forward and shortened.
        val copied = bus.copyStereo(out, 400, 200)
        assertEquals(100, copied)
        assertEquals(500 / 1000f, out[0], 1e-6f)
        assertEquals(599 / 1000f, out[198], 1e-6f)

        // Entirely expired data, and data past the write cursor, return nothing.
        assertEquals(0, bus.copyStereo(out, 0, 10))
        assertEquals(0, bus.copyStereo(out, 1500, 10))
    }

    @Test
    fun copyLatestReturnsTheNewestFramesAfterWrapping() {
        val bus = newBus(rate = 1000, historySeconds = 1f)
        bus.writeRamp(2500)
        val out = FloatArray(8)
        assertEquals(4, bus.copyLatestStereo(out, 4))
        // The newest four frames are 2496..2499.
        assertEquals(2496 / 1000f, out[0], 1e-6f)
        assertEquals(2499 / 1000f, out[6], 1e-6f)
    }

    @Test
    fun envelopeReportsMinAndMaxPerColumn() {
        val bus = newBus()
        val frames = 800
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val l = if (i % 2 == 0) 0.5f else -0.5f
            buf[i * 2] = l
            buf[i * 2 + 1] = -0.25f
        }
        bus.write(buf, frames, 2)

        val columns = 4
        val out = FloatArray(columns * AudioBus.COLUMNS_STRIDE)
        assertEquals(columns, bus.stereoEnvelope(out, 0, frames, columns))
        for (c in 0 until columns) {
            val base = c * AudioBus.COLUMNS_STRIDE
            assertEquals("minL", -0.5f, out[base], 1e-6f)
            assertEquals("maxL", 0.5f, out[base + 1], 1e-6f)
            assertEquals("minR", -0.25f, out[base + 2], 1e-6f)
            assertEquals("maxR", -0.25f, out[base + 3], 1e-6f)
            // sum = L + R -> L = -0.5 gives -0.75, L = 0.5 gives 0.25
            assertEquals(-0.75f, out[base + 4], 1e-6f)
            assertEquals(0.25f, out[base + 5], 1e-6f)
            // diff = L - R -> L = -0.5 gives -0.25, L = 0.5 gives 0.75
            assertEquals(-0.25f, out[base + 6], 1e-6f)
            assertEquals(0.75f, out[base + 7], 1e-6f)
        }
    }

    @Test
    fun envelopeFillsEveryColumnWhenFramesAreFewerThanColumns() {
        val bus = newBus()
        val buf = floatArrayOf(0.1f, -0.1f, 0.9f, -0.9f)
        bus.write(buf, 2, 2)
        val columns = 4
        val out = FloatArray(columns * AudioBus.COLUMNS_STRIDE)
        assertEquals(columns, bus.stereoEnvelope(out, 0, 2, columns))
        assertEquals(0.1f, out[0], 1e-6f)
        assertEquals(0.1f, out[AudioBus.COLUMNS_STRIDE], 1e-6f)
        assertEquals(0.9f, out[2 * AudioBus.COLUMNS_STRIDE], 1e-6f)
        assertEquals(0.9f, out[3 * AudioBus.COLUMNS_STRIDE], 1e-6f)
    }

    @Test
    fun awaitFramesTimesOutWhenNothingIsWritten() {
        val bus = newBus()
        assertFalse(bus.awaitFrames(0, 32, 40))
    }

    @Test
    fun awaitFramesWakesUpWhenTheCaptureThreadWrites() {
        val bus = newBus()
        val writer = thread {
            Thread.sleep(60)
            bus.writeRamp(64)
        }
        assertTrue(bus.awaitFrames(0, 64, 2000))
        writer.join()
        assertEquals(64L, bus.totalFrames)
    }

    @Test
    fun stopReleasesWaiters() {
        val bus = newBus()
        val released = thread {
            assertFalse(bus.awaitFrames(0, 64, 3000))
        }
        Thread.sleep(50)
        bus.stop()
        released.join(1000)
        assertFalse(released.isAlive)
    }
}

package com.litescope.dsp

import com.litescope.core.FreqAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FftTest {

    /** Reference DFT so the radix-2 implementation is checked against first principles. */
    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<FloatArray, FloatArray> {
        val n = re.size
        val outRe = FloatArray(n)
        val outIm = FloatArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                sr += re[t] * cos(angle) - im[t] * sin(angle)
                si += re[t] * sin(angle) + im[t] * cos(angle)
            }
            outRe[k] = sr.toFloat()
            outIm[k] = si.toFloat()
        }
        return outRe to outIm
    }

    @Test
    fun matchesNaiveDftForMixedSignal() {
        val n = 64
        val fft = Fft(n)
        val re = FloatArray(n) { i -> (sin(2 * PI * 3 * i / n) + 0.5 * cos(2 * PI * 7 * i / n)).toFloat() }
        val im = FloatArray(n)
        val expected = naiveDft(re.copyOf(), im.copyOf())
        fft.transform(re, im)
        for (k in 0 until n) {
            assertEquals("re[$k]", expected.first[k], re[k], 1e-3f)
            assertEquals("im[$k]", expected.second[k], im[k], 1e-3f)
        }
    }

    @Test
    fun singleBinSineHasExpectedAmplitude() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { i -> sin(2.0 * PI * 64 * i / n).toFloat() }
        val im = FloatArray(n)
        fft.transform(re, im)
        val mags = FloatArray(fft.bins)
        fft.magnitudes(re, im, mags)
        // A full scale sine puts n/2 in its own bin and (almost) nothing in its neighbours.
        assertEquals(n / 2f, mags[64], n / 100f)
        assertTrue("neighbour bin should be tiny", mags[65] < n / 100f)
    }

    @Test
    fun onlyPowersOfTwoAreAccepted() {
        try {
            Fft(100)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}

class WindowsTest {

    @Test
    fun coherentGainMatchesCoefficientMean() {
        for (type in WindowType.entries) {
            val coeffs = Windows.coefficients(type, 512)
            val mean = coeffs.sum() / coeffs.size
            assertEquals(type.name, Windows.coherentGain(type), mean, 5e-3f)
        }
    }

    @Test
    fun hannTapersToZero() {
        val coeffs = Windows.coefficients(WindowType.HANN, 256)
        assertEquals(0f, coeffs.first(), 1e-6f)
        assertEquals(0f, coeffs.last(), 1e-6f)
        assertEquals(1f, coeffs[128], 5e-3f)
    }
}

class DecimatorTest {

    @Test
    fun passbandIsPreserved() {
        val factor = 4
        val decimator = FirDecimator(factor)
        val n = 8192
        val input = FloatArray(n) { i -> sin(2.0 * PI * 100 * i / 48000.0).toFloat() }
        val out = FloatArray(n / factor + 4)
        val written = decimator.process(input, n, out)
        // Skip the filter transient, then check the amplitude is roughly unity.
        var peak = 0f
        for (i in 200 until written) peak = maxOf(peak, abs(out[i]))
        assertEquals(1f, peak, 0.05f)
    }

    @Test
    fun stopbandIsAttenuated() {
        val factor = 4
        val decimator = FirDecimator(factor)
        val n = 8192
        // 20 kHz would alias onto 8 kHz if it were not filtered before decimation.
        val input = FloatArray(n) { i -> sin(2.0 * PI * 20000 * i / 48000.0).toFloat() }
        val out = FloatArray(n / factor + 4)
        val written = decimator.process(input, n, out)
        var peak = 0f
        for (i in 200 until written) peak = maxOf(peak, abs(out[i]))
        assertTrue("aliased 20 kHz should be suppressed, saw $peak", peak < 0.05f)
    }
}

class FreqAxisTest {

    private fun axis(maxHz: Float = 24000f, blend: Float = 1f) = FreqAxis().apply {
        configure(maxHz, blend)
        reset()
    }

    @Test
    fun roundTripsInLinearMode() {
        val a = axis(blend = 0f)
        a.setRange(1000f, 5000f)
        for (hz in intArrayOf(1000, 2000, 3000, 4999)) {
            val x = a.hzToX(hz.toFloat(), 1000f)
            assertEquals(hz.toFloat(), a.xToHz(x, 1000f), 1f)
        }
    }

    @Test
    fun roundTripsInLogMode() {
        val a = axis(blend = 1f)
        for (hz in intArrayOf(20, 100, 1000, 15000, 23000)) {
            val x = a.hzToX(hz.toFloat(), 800f)
            assertEquals(hz.toFloat(), a.xToHz(x, 800f), hz * 0.01f + 1f)
        }
    }

    @Test
    fun roundTripsWithABlendedScale() {
        val a = axis(blend = 0.5f)
        for (hz in intArrayOf(30, 200, 1500, 12000, 22000)) {
            val x = a.hzToX(hz.toFloat(), 900f)
            assertEquals("blended axis must invert cleanly at $hz Hz", hz.toFloat(), a.xToHz(x, 900f), hz * 0.02f + 2f)
        }
    }

    @Test
    fun blendIsMonotonicAcrossTheBand() {
        for (blend in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val a = axis(blend = blend)
            var previous = -1f
            for (step in 0..50) {
                val hz = a.xToHz(step / 50f * 1000f, 1000f)
                assertTrue("blend $blend must be monotonic", hz > previous)
                previous = hz
            }
        }
    }

    @Test
    fun zoomKeepsFocusVisibleAndStaysInRange() {
        val a = axis(blend = 1f)
        a.zoomAt(1000f, 0.5f)
        assertTrue("focus should stay visible", a.startHz < 1000f && a.endHz > 1000f)
        assertTrue("span must shrink", a.spanHz < 24000f)
        repeat(30) { a.zoomAt(1000f, 2f) }
        assertTrue(a.startHz >= 0f)
        assertTrue(a.endHz <= 24000.1f)
    }

    @Test
    fun zoomInNarrowsTheVisibleWindow() {
        val a = axis(blend = 1f)
        val before = a.spanHz
        a.zoomAt(2000f, 0.25f)
        assertTrue("zooming in must narrow the window", a.spanHz < before * 0.5f)
        a.zoomAt(2000f, 4f)
        assertTrue("zooming out must widen it again", a.spanHz > before * 0.8f)
    }

    @Test
    fun panStopsAtTheBandEdges() {
        val a = axis(blend = 0f)
        a.setRange(1000f, 3000f)
        a.panByFraction(100f)
        assertEquals(24000f, a.endHz, 1f)
        a.panByFraction(-100f)
        assertEquals(0f, a.startHz, 1f)
    }

    @Test
    fun copyFromMirrorsTheWindow() {
        val source = axis(blend = 0.5f)
        source.setRange(300f, 6000f)
        val target = axis(blend = 0.5f)
        target.copyFrom(source)
        assertEquals(source.startHz, target.startHz, 0.5f)
        assertEquals(source.endHz, target.endHz, 0.5f)
    }
}

class NotesTest {

    @Test
    fun a4IsConcertPitch() {
        assertEquals("A4 +0c", Notes.describe(440f))
    }

    @Test
    fun tuningShiftsTheNoteReadout() {
        // A4 stays A4 at its own reference, and a lower reference makes the same tone read sharp.
        assertEquals("A4 +0c", Notes.describe(440f, 440f))
        assertEquals("A4 +0c", Notes.describe(415f, 415f))
        // A reference a little flat makes the same tone read flat against that reference.
        assertEquals("A4 -8c", Notes.describe(440f, 442f))
        // A reference a semitone flat pushes 440 Hz onto the next semitone, one cent sharp.
        assertEquals("A#4 +1c", Notes.describe(440f, 415f))
    }

    @Test
    fun octavesAreLabelled() {
        assertEquals("A3 +0c", Notes.describe(220f))
        assertEquals("A5 +0c", Notes.describe(880f))
    }

    @Test
    fun dbConversionIsSymmetric() {
        assertEquals(0f, Notes.toDb(1f), 1e-3f)
        assertEquals(-6.02f, Notes.toDb(0.5f), 0.01f)
        assertEquals(0.5f, Notes.fromDb(-6.0206f), 1e-3f)
        assertTrue(sqrt(0.25f) == 0.5f)
    }
}

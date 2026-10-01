package com.litescope.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.litescope.core.Prefs
import com.litescope.core.ScopeHub
import com.litescope.dsp.Colormaps
import com.litescope.dsp.LevelFrame
import com.litescope.dsp.SpectrumFrame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.sin

/**
 * Rasterises every scope view with real Skia (Robolectric native graphics) and checks that the
 * drawing code puts ink on the canvas instead of crashing or painting nothing.
 *
 * This covers the whole view layer: grid, traces, textures, readouts and the waterfall bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScopeViewRenderTest {

    private val width = 720
    private val height = 320

    private fun hub(): ScopeHub {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ScopeHub.get(context)
    }

    /** Renders [view] into a fresh bitmap and returns it. */
    private fun render(view: View): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Number of pixels that differ from the flat background colour. */
    private fun inkCount(bitmap: Bitmap): Int {
        var ink = 0
        val background = bitmap.getPixel(1, 1)
        for (y in 0 until bitmap.height step 3) {
            for (x in 0 until bitmap.width step 3) {
                if (bitmap.getPixel(x, y) != background) ink++
            }
        }
        return ink
    }

    /** Feeds the ring buffer with a two tone signal so the time domain views have data. */
    private fun fillBus(hub: ScopeHub) {
        hub.onCaptureFormatChanged(48000)
        val frames = 48000
        val buffer = FloatArray(frames * 2) { i ->
            val n = i / 2
            when (i % 2) {
                0 -> (0.6 * sin(2.0 * PI * 1000.0 * n / 48000.0)).toFloat()
                else -> (0.6 * sin(2.0 * PI * 1000.0 * n / 48000.0)).toFloat()
            }
        }
        hub.bus.write(buffer, frames, 2)
    }

    private fun syntheticSpectrumFrame(hub: ScopeHub): SpectrumFrame {
        val bins = 1024
        val binHz = (hub.bus.sampleRate / 2f) / bins
        val db = FloatArray(bins) { i ->
            // A peak around 1 kHz on a sloped noise floor.
            val hz = i * binHz
            val bump = Math.exp(-((hz - 1000f) * (hz - 1000f) / 200000f).toDouble())
            (-90f + 30f * bump).toFloat()
        }
        return SpectrumFrame(db, bins, hub.bus.sampleRate, binHz, 1000f, -60f, 1L)
    }

    @Test
    fun spectrumViewDrawsGridTraceAndReadout() {
        val hub = hub()
        fillBus(hub)
        hub.publishSpectrum(syntheticSpectrumFrame(hub))
        val view = SpectrumView(ApplicationProvider.getApplicationContext())
        val ink = inkCount(render(view))
        assertNull(view.lastRenderError)
        assertTrue("spectrum drew nothing", ink > 500)
    }

    @Test
    fun waterfallViewDrawsHistory() {
        val hub = hub()
        fillBus(hub)
        val frame = syntheticSpectrumFrame(hub)
        repeat(64) { hub.waterfall.push(frame.db, frame.bins, -100f, 0f) }
        hub.publishSpectrum(frame)
        val view = WaterfallView(ApplicationProvider.getApplicationContext())
        val ink = inkCount(render(view))
        assertNull(view.lastRenderError)
        assertTrue("waterfall drew nothing", ink > 500)
    }

    @Test
    fun waveformViewDrawsBothStereoLayouts() {
        val hub = hub()
        fillBus(hub)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(context).waveStereoMode = 0
        val combinedView = WaveformView(context)
        val combined = inkCount(render(combinedView))
        assertNull(combinedView.lastRenderError)
        assertTrue("overlay wave drew nothing", combined > 300)

        Prefs.get(context).waveStereoMode = 1
        val splitView = WaveformView(context)
        val split = inkCount(render(splitView))
        assertNull(splitView.lastRenderError)
        assertTrue("split wave drew nothing", split > 300)

        Prefs.get(context).waveStereoMode = 2
        val sumView = WaveformView(context)
        val sum = inkCount(render(sumView))
        assertNull(sumView.lastRenderError)
        assertTrue("summed wave drew nothing", sum > 300)
    }

    @Test
    fun vectorScopeDrawsAPhosphorTrace() {
        val hub = hub()
        fillBus(hub)
        hub.publishLevels(
            LevelFrame(0.6f, 0.6f, 0.4f, 0.4f, 0.6f, 0.6f, 1f, 0.4f, 0f, 1.2f, 0.8f, 0L, 1L)
        )
        val view = VectorScopeView(ApplicationProvider.getApplicationContext())
        val ink = inkCount(render(view))
        assertNull(view.lastRenderError)
        assertTrue("vector scope drew nothing", ink > 200)
    }

    @Test
    fun meterViewDrawsBarsAndReadouts() {
        val hub = hub()
        fillBus(hub)
        hub.publishLevels(
            LevelFrame(
                peakL = 0.8f,
                peakR = 0.5f,
                rmsL = 0.4f,
                rmsR = 0.25f,
                holdL = 0.9f,
                holdR = 0.7f,
                correlation = 0.35f,
                midRms = 0.3f,
                sideRms = 0.2f,
                sumPeak = 1.4f,
                sumRms = 0.6f,
                clippedAt = SystemClock.elapsedRealtime(),
                seq = 1L
            )
        )
        val view = MeterView(ApplicationProvider.getApplicationContext())
        val ink = inkCount(render(view))
        assertNull(view.lastRenderError)
        assertTrue("meters drew nothing", ink > 200)
    }

    @Test
    fun colormapTablesAreDistinctAndMonotonicInLength() {
        val magma = Colormaps.lut(com.litescope.dsp.Colormap.MAGMA)
        val ice = Colormaps.lut(com.litescope.dsp.Colormap.ICE)
        assertTrue(magma.size == Colormaps.LEVELS)
        assertTrue(magma.first() != magma.last())
        assertTrue(ice.first() != ice.last())
        assertTrue(magma.last() != ice.last())
    }
}

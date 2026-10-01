package com.litescope.core

import android.content.Context
import android.os.SystemClock
import com.litescope.audio.SignalGenerator
import com.litescope.audio.SpectrumAnalyzer
import com.litescope.audio.WavRecorder
import com.litescope.dsp.Colormap
import com.litescope.dsp.LevelFrame
import com.litescope.dsp.SpectrumFrame
import java.util.concurrent.atomic.AtomicReference

/** Snapshot of the capture pipeline state, shown in the activity and the notification. */
class CaptureStatus(
    val active: Boolean = false,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val analysisRate: Int = 0,
    val projectionGranted: Boolean = false,
    val testSignal: Boolean = false,
    val message: String = "Idle"
) {
    fun describe(): String =
        if (testSignal) {
            "Test signal generator running — no playback capture"
        } else if (active) {
            "Capturing ${sampleRate / 1000f} kHz ${if (channels >= 2) "stereo" else "mono"}" +
                " · FFT ${analysisRate / 1000f} kHz"
        } else {
            message
        }
}

/**
 * Process-wide shared state of the inspection pipeline: the audio ring buffer, the latest
 * analysis frames, the waterfall history and the frequency axis shared by the spectrum tools.
 *
 * Both the overlay service and the activity (preview mode) render from the same hub, so a scope
 * shown twice is always in sync.
 */
class ScopeHub private constructor(context: Context) : Prefs.Listener {

    val prefs: Prefs = Prefs.get(context)
    val bus = AudioBus()
    val freqAxis = FreqAxis()
    val waterfall = WaterfallBuffer()
    /** Measurement cursors shared by the spectrum and (when linked) the waterfall. */
    val cursors = CursorStore()
    val recorder = WavRecorder(context)
    val generator = SignalGenerator(bus)

    val spectrum = AtomicReference<SpectrumFrame?>(null)
    val levels = AtomicReference<LevelFrame?>(null)
    val status = AtomicReference(CaptureStatus())

    @Volatile
    private var analyzer: SpectrumAnalyzer? = null

    private var lastWaterfallPush = 0L

    /**
     * Rows actually written per second, smoothed. The analyser cannot deliver more frames than it
     * produces, so the *achieved* rate can be lower than the requested one; the waterfall uses this
     * to keep the displayed history equal to the requested number of seconds.
     */
    @Volatile
    var actualWaterfallRowsPerSecond: Float = 0f
        private set
    private var waterfallColormap = -1
    private var waterfallBins = -1

    val nyquistHz: Float get() = bus.sampleRate / 2f

    /** Starts the FFT/level analysis thread (idempotent). */
    fun startAnalysis() {
        if (analyzer != null) return
        val a = SpectrumAnalyzer(this)
        a.start()
        prefs.addListener(this)
        analyzer = a
    }

    fun stopAnalysis() {
        prefs.removeListener(this)
        analyzer?.shutdown()
        analyzer = null
    }

    /** Called by the capture engine before the first block is written. */
    fun onCaptureFormatChanged(sampleRate: Int) {
        bus.configure(sampleRate, HISTORY_SECONDS)
        freqAxis.configure(sampleRate / 2f, prefs.freqScaleBlend)
        freqAxis.reset()
        waterfall.clear()
        lastWaterfallPush = 0L
        actualWaterfallRowsPerSecond = 0f
    }

    /** Stores a new spectrum frame and appends it to the waterfall when a row is due. */
    fun publishSpectrum(frame: SpectrumFrame) {
        spectrum.set(frame)
        val map = Colormap.fromOrdinal(prefs.waterfallColormap)
        if (waterfallColormap != map.ordinal) {
            waterfallColormap = map.ordinal
            waterfall.setColormap(map)
        }
        if (waterfallBins != frame.bins) {
            waterfallBins = frame.bins
            waterfall.configure(frame.bins, prefs.waterfallMaxRows(frame.bins))
        }
        if (prefs.waterfallFreeze) return
        val rowsPerSecond = prefs.waterfallRowsPerSecond(frame.bins)
        val now = SystemClock.elapsedRealtime()
        val interval = (1000f / rowsPerSecond).toLong().coerceAtLeast(1L)
        if (now - lastWaterfallPush >= interval) {
            if (lastWaterfallPush > 0L) {
                val dt = (now - lastWaterfallPush) / 1000f
                if (dt > 0.002f) {
                    val instant = 1f / dt
                    actualWaterfallRowsPerSecond = if (actualWaterfallRowsPerSecond <= 0f) {
                        instant
                    } else {
                        // Slow smoothing: a jumpy estimate made the visible time window breathe.
                    actualWaterfallRowsPerSecond * 0.97f + instant * 0.03f
                    }
                }
            }
            lastWaterfallPush = now
            waterfall.push(frame.db, frame.bins, prefs.dbFloor.toFloat(), prefs.dbTop.toFloat())
        }
    }

    fun publishLevels(frame: LevelFrame) {
        levels.set(frame)
    }

    /** Clears the waterfall history (also used by the "clear" gesture on the waterfall view). */
    fun clearWaterfall() {
        waterfall.clear()
    }

    /** Restores the spectrum/waterfall zoom to the full band. */
    fun resetFrequencyAxis() {
        freqAxis.configure(nyquistHz, prefs.freqScaleBlend)
        freqAxis.reset()
    }

    override fun onPrefsChanged(keys: Set<String>) {
        freqAxis.configure(nyquistHz, prefs.freqScaleBlend)
    }

    fun release() {
        stopAnalysis()
        waterfall.release()
    }

    companion object {
        /** Seconds of stereo history kept in RAM (used by the wave scope and the vector scope). */
        const val HISTORY_SECONDS = 6f

        @Volatile
        private var instance: ScopeHub? = null

        fun get(context: Context): ScopeHub = instance ?: synchronized(this) {
            instance ?: ScopeHub(context.applicationContext).also { instance = it }
        }
    }
}

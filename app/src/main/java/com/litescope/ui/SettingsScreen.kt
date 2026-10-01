package com.litescope.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.litescope.R
import com.litescope.core.CursorStore
import com.litescope.core.Prefs
import com.litescope.core.Tool
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.litescope.view.ScopeTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Builds the whole settings UI programmatically: every row is bound to one [Prefs] value and
 * re-reads it whenever anything changes (including changes made from the notification panel).
 *
 * Sliders are precise: the value field is editable for exact entry, and double tapping a slider
 * snaps it back to its default.
 */
class SettingsScreen(
    private val context: Context,
    private val prefs: Prefs,
    private val actions: Actions
) : Prefs.Listener {

    interface Actions {
        /** Effective capture sample rate in Hz, 0 when nothing is captured yet. */
        fun effectiveCaptureRate(): Int

        fun isRecording(): Boolean
        fun toggleRecording()

        /** Starts/stops the built-in signal generator (used to test the scopes without playback). */
        fun setTestSignal(enabled: Boolean)

        fun isTestSignalRunning(): Boolean
        fun resetWindowLayout()
        fun resetAllSettings()

        /** Removes every measurement cursor from the spectrum and waterfall. */
        fun clearCursors()
    }

    private val density = context.resources.displayMetrics.density
    private val updaters = ArrayList<() -> Unit>()
    private var suppress = false

    private val palette: com.litescope.view.Palette get() = ScopeTheme.palette(prefs)

    private val accent: Int get() = palette.accent

    fun attach() {
        prefs.addListener(this)
    }

    fun detach() {
        prefs.removeListener(this)
    }

    override fun onPrefsChanged(keys: Set<String>) {
        suppress = true
        for (u in updaters) u()
        suppress = false
    }

    fun build(): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(4), dp(12), dp(28))

        buildCapture(root)
        buildSpectrum(root)
        buildWaterfall(root)
        buildWaveform(root)
        buildVector(root)
        buildMeters(root)
        buildWindows(root)
        buildAppearance(root)

        addButton(root, "Reset all settings") { actions.resetAllSettings() }
        return root
    }

    // ------------------------------------------------------------------ sections

    private fun buildCapture(root: LinearLayout) {
        val card = section(root, R.string.sec_capture)

        addChoice(
            card,
            "Capture sample rate",
            listOf("Auto", "48 kHz", "44.1 kHz", "96 kHz", "192 kHz"),
            summary = {
                val requested = prefs.captureRate
                val effective = actions.effectiveCaptureRate()
                val base = if (requested == Prefs.RATE_AUTO) {
                    "Automatic (device default)"
                } else {
                    "Requested ${hz(requested)}"
                }
                base + (if (effective > 0) " · using ${hz(effective)}" else "") +
                    " · capture restarts automatically"
            },
            get = { Prefs.CAPTURE_RATE_OPTIONS.indexOf(prefs.captureRate).coerceAtLeast(0) },
            set = { prefs.captureRate = Prefs.CAPTURE_RATE_OPTIONS[it] }
        )

        val captureRate = actions.effectiveCaptureRate().let { if (it > 0) it else 48000 }
        val divisors = prefs.divisorOptions(captureRate)
        addChoice(
            card,
            "Spectrum analysis rate",
            divisors.map { hz(it.second) },
            summary = {
                "FFT runs at ${hz(prefs.analysisRate(captureRate))} (capture ÷ ${prefs.analysisDivisor}) — applies instantly"
            },
            get = { divisors.indexOfFirst { it.first == prefs.analysisDivisor }.coerceAtLeast(0) },
            set = { prefs.analysisDivisor = divisors[it].first }
        )

        addChoice(
            card,
            "Spectrum input channel",
            listOf("L + R", "Left", "Right"),
            summary = { "Channel fed to the spectrum and waterfall analyser" },
            get = { prefs.spectrumChannel },
            set = { prefs.spectrumChannel = it }
        )

        addButton(card, if (actions.isRecording()) "Stop WAV recording" else "Record captured audio to WAV") {
            actions.toggleRecording()
        }

        addSubHeader(card, "Self test")
        addSwitch(
            card,
            "Test signal generator",
            {
                if (actions.isTestSignalRunning()) {
                    "Running — playback capture is paused while the generator is on"
                } else {
                    "Feeds a synthetic signal into the scopes so they can be checked or calibrated without playing audio"
                }
            },
            { prefs.testSignal },
            { actions.setTestSignal(it) }
        )

        addChoice(
            card,
            "Test signal",
            listOf("1 kHz tone", "Sweep 20 Hz – 20 kHz", "440 L / 660 R", "Pink noise", "White noise"),
            summary = { "The 1 kHz tone is exactly full scale at level 1.00" },
            get = { prefs.testSignalType },
            set = { prefs.testSignalType = it }
        )

        addSlider(
            card, "Test signal level", 0f, 1f, false, 0.5f,
            format = { String.format(java.util.Locale.US, "%.2f FS", it) },
            summary = { "Peak amplitude of the generated signal" },
            get = { prefs.testSignalLevel },
            set = { prefs.testSignalLevel = it }
        )
    }

    private fun buildSpectrum(root: LinearLayout) {
        val card = section(root, R.string.sec_spectrum)

        addChoice(
            card,
            "FFT size",
            Prefs.FFT_SIZE_OPTIONS.map { it.toString() },
            summary = {
                val rate =
                    prefs.analysisRate(actions.effectiveCaptureRate().let { if (it > 0) it else 48000 })
                "Frequency resolution ${hz(rate / prefs.fftSize)} per bin"
            },
            get = { Prefs.FFT_SIZE_OPTIONS.indexOf(prefs.fftSize).coerceAtLeast(0) },
            set = { prefs.fftSize = Prefs.FFT_SIZE_OPTIONS[it] }
        )

        addChoice(
            card,
            "Window",
            listOf("Hann", "Hamming", "Blackman-Harris", "Flat top", "Rect"),
            summary = { "Windowing function applied before the FFT" },
            get = {
                when (prefs.windowType) {
                    "HAMMING" -> 1
                    "BLACKMAN_HARRIS" -> 2
                    "FLAT_TOP" -> 3
                    "RECTANGULAR" -> 4
                    else -> 0
                }
            },
            set = {
                prefs.windowType = when (it) {
                    1 -> "HAMMING"
                    2 -> "BLACKMAN_HARRIS"
                    3 -> "FLAT_TOP"
                    4 -> "RECTANGULAR"
                    else -> "HANN"
                }
            }
        )

        addChoice(
            card,
            "Frame overlap",
            listOf("None", "50 %", "75 %"),
            summary = { "More overlap means a smoother, faster updating trace" },
            get = { prefs.fftOverlap },
            set = { prefs.fftOverlap = it }
        )

        addSlider(
            card, "Averaging", 0f, 0.95f, false, 0.55f,
            format = { String.format(java.util.Locale.US, "%.2f", it) },
            summary = { if (prefs.spectrumAveraging < 0.05f) "Raw frames" else "Exponential averaging" },
            get = { prefs.spectrumAveraging },
            set = { prefs.spectrumAveraging = it }
        )

        addSlider(
            card, "Frequency scale", 0f, 1f, false, 1f,
            format = { String.format(java.util.Locale.US, "%.2f", it) },
            summary = {
                val blend = prefs.freqScaleBlend
                when {
                    blend <= 0.02f -> "Pure linear axis"
                    blend >= 0.98f -> "Pure logarithmic axis"
                    else -> "${(blend * 100).roundToInt()} % logarithmic · ${100 - (blend * 100).roundToInt()} % linear"
                }
            },
            get = { prefs.freqScaleBlend },
            set = { prefs.freqScaleBlend = it }
        )

        addSwitch(
            card,
            "Peak hold",
            { "Long press the scope to clear the held trace; double tap only resets the zoom" },
            { prefs.peakHold },
            { prefs.peakHold = it }
        )

        addSlider(
            card, "Peak decay", 0f, 60f, false, 14f,
            format = { String.format(java.util.Locale.US, "%.0f dB/s", it) },
            summary = { "How fast the peak trace falls back" },
            get = { prefs.peakDecayDbPerSec },
            set = { prefs.peakDecayDbPerSec = it }
        )

        addSwitch(
            card,
            "Pink tilt",
            { "Applies +${String.format(java.util.Locale.US, "%.1f", prefs.tiltDbPerOctave)} dB/octave so pink noise looks flat" },
            { prefs.spectrumTilt },
            { prefs.spectrumTilt = it }
        )

        addSlider(
            card, "Tilt slope", 0f, 6f, false, 3f,
            format = { String.format(java.util.Locale.US, "%.1f dB/oct", it) },
            summary = { "3 dB/octave matches pink noise" },
            get = { prefs.tiltDbPerOctave },
            set = { prefs.tiltDbPerOctave = it }
        )

        addDbRange(card)

        addInfo(
            card,
            {
                "Cursors: tap the scope to add one, drag its handle to move it, tap it to remove it. " +
                    "Long press a cursor to lock it; unlocked cursors hide after " +
                    "${CursorStore.AUTO_HIDE_MS / 1000} s without input."
            }
        )

        addSwitch(
            card,
            "Cursor note",
            { "Prints the equivalent musical note next to every cursor and next to the peak readout" },
            { prefs.cursorNoteEnabled },
            { prefs.cursorNoteEnabled = it }
        )

        addSlider(
            card, "Tuning (A4)", 400f, 480f, false, 440f,
            format = { String.format(java.util.Locale.US, "%.0f Hz", it) },
            summary = {
                val note = com.litescope.dsp.Notes.describe(prefs.tuningHz.toFloat(), prefs.tuningHz.toFloat())
                "Reference for the note readout — A4 is ${prefs.tuningHz} Hz ($note)"
            },
            get = { prefs.tuningHz.toFloat() },
            set = { prefs.tuningHz = it.roundToInt() }
        )

        addButton(card, "Clear all measurement cursors") { actions.clearCursors() }
    }

    private fun buildWaterfall(root: LinearLayout) {
        val card = section(root, R.string.sec_waterfall)

        addSlider(
            card, "History length", 2f, 300f, true, 12f,
            format = { String.format(java.util.Locale.US, "%.0f s", it) },
            summary = {
                "Stored ${prefs.waterfallMaxRows(prefs.fftSize / 2)} rows max · " +
                    String.format(
                        java.util.Locale.US, "%.1f",
                        prefs.waterfallRowsPerSecond(prefs.fftSize / 2)
                    ) + " rows/s"
            },
            get = { prefs.waterfallHistorySec },
            set = { prefs.waterfallHistorySec = it }
        )

        addChoice(
            card,
            "Colour map",
            listOf("Magma", "Viridis", "Inferno", "Fire", "Ice", "Rainbow", "Gray"),
            summary = { "Tap the waterfall to freeze it, long press to clear" },
            get = { prefs.waterfallColormap },
            set = { prefs.waterfallColormap = it }
        )

        addSwitch(
            card,
            "Newest row on top",
            { "Off scrolls the history upwards instead" },
            { prefs.waterfallNewestOnTop },
            { prefs.waterfallNewestOnTop = it }
        )

        addSlider(
            card, "Row rate", 5f, 60f, false, 24f,
            format = { String.format(java.util.Locale.US, "%.0f rows/s", it) },
            summary = { "Upper bound; the pixel budget may reduce it to keep the history length" },
            get = { prefs.waterfallFps.toFloat() },
            set = { prefs.waterfallFps = it.roundToInt() }
        )

        addSwitch(
            card,
            "Sync with spectrum",
            { "Mirrors the spectrum zoom, pan, scale and cursors. Also the link button in the waterfall title bar" },
            { prefs.linkZoom },
            { prefs.linkZoom = it }
        )

        addSwitch(
            card,
            "Freeze",
            { "Pauses the waterfall while the spectrum keeps running" },
            { prefs.waterfallFreeze },
            { prefs.waterfallFreeze = it }
        )
    }

    private fun buildWaveform(root: LinearLayout) {
        val card = section(root, R.string.sec_waveform)

        addChoice(
            card,
            "Stereo view",
            listOf("Overlay", "Split", "Sum"),
            summary = {
                when (prefs.waveStereoMode) {
                    1 -> "Left and right in separate lanes with their own gain"
                    2 -> "The two channels literally added into one trace (L + R)"
                    else -> "Both channels overlaid in one lane"
                }
            },
            get = { prefs.waveStereoMode },
            set = { prefs.waveStereoMode = it }
        )

        addSlider(
            card, "Time base", 0.02f, 200f, true, 5f,
            format = { String.format(java.util.Locale.US, "%.2f ms/div", it) },
            summary = {
                String.format(
                    java.util.Locale.US,
                    "%.1f ms across 10 divisions · pinch to zoom, drag to pan, tap to freeze",
                    prefs.waveMsPerDiv * 10f
                )
            },
            get = { prefs.waveMsPerDiv },
            set = { prefs.waveMsPerDiv = it }
        )

        addSwitch(
            card,
            "Link L/R gain",
            {
                if (prefs.waveLinkGain) {
                    "Both channels always share one gain (drag or slider)"
                } else {
                    "Each channel keeps its own gain"
                }
            },
            { prefs.waveLinkGain },
            { prefs.waveLinkGain = it }
        )

        addSlider(
            card, "Gain L", -40f, 60f, false, 0f,
            format = { String.format(java.util.Locale.US, "%+.1f dB", it) },
            summary = {
                if (prefs.waveLinkGain) "Vertical drag inside the scope changes both channels"
                else "Vertical drag changes the lane you started in"
            },
            get = { prefs.waveGainDbL },
            set = {
                prefs.waveGainDbL = it
                if (prefs.waveLinkGain) prefs.waveGainDbR = it
            }
        )

        addSlider(
            card, "Gain R", -40f, 60f, false, 0f,
            format = { String.format(java.util.Locale.US, "%+.1f dB", it) },
            summary = { if (prefs.waveLinkGain) "Locked to Gain L" else "Used in the split stereo layout" },
            get = { prefs.waveGainDbR },
            set = { prefs.waveGainDbR = it }
        )

        addChoice(
            card,
            "Trace source",
            listOf("L / R", "L", "R", "Mid", "Side"),
            summary = { "Which signal the overlay layout draws" },
            get = { prefs.waveSource },
            set = { prefs.waveSource = it }
        )

        addChoice(
            card,
            "Trigger mode",
            listOf("Auto", "Normal", "Free run"),
            summary = { "Auto and Normal stabilise periodic signals; free run shows the live edge" },
            get = { prefs.waveTriggerMode },
            set = { prefs.waveTriggerMode = it }
        )

        addChoice(
            card,
            "Trigger edge",
            listOf("Rising", "Falling"),
            summary = { "Edge the trigger searches for" },
            get = { prefs.waveTriggerEdge },
            set = { prefs.waveTriggerEdge = it }
        )

        addChoice(
            card,
            "Trigger source",
            listOf("L + R", "L", "R"),
            summary = { "Signal used for the trigger comparator" },
            get = { prefs.waveTriggerSource },
            set = { prefs.waveTriggerSource = it }
        )

        addSlider(
            card, "Trigger level", -1f, 1f, false, 0f,
            format = { String.format(java.util.Locale.US, "%+.3f FS", it) },
            summary = { "Marked by the dashed line in the scope" },
            get = { prefs.waveTriggerLevel },
            set = { prefs.waveTriggerLevel = it }
        )

        addSwitch(
            card,
            "AC coupling",
            { "Removes the DC offset before drawing" },
            { prefs.waveAcCoupling },
            { prefs.waveAcCoupling = it }
        )

        addSwitch(
            card,
            "Measurements",
            { "Vpp, RMS and peak readout, plus a rough frequency estimate" },
            { prefs.waveShowMeasurements },
            { prefs.waveShowMeasurements = it }
        )
    }

    private fun buildVector(root: LinearLayout) {
        val card = section(root, R.string.sec_vector)

        addChoice(
            card,
            "Mode",
            listOf("Lissajous", "Goniometer"),
            summary = {
                if (prefs.vectorMode == 0) "X = left, Y = right"
                else "Rotated 45°: mid at the top, side at the right"
            },
            get = { prefs.vectorMode },
            set = { prefs.vectorMode = it }
        )

        addSlider(
            card, "Persistence", 0f, 0.97f, false, 0.72f,
            format = { String.format(java.util.Locale.US, "%.2f", it) },
            summary = { "Higher values leave a longer phosphor trail" },
            get = { prefs.vectorPersistence },
            set = { prefs.vectorPersistence = it }
        )

        addSlider(
            card, "Gain", 0.05f, 8f, true, 1f,
            format = { String.format(java.util.Locale.US, "%.2f×", it) },
            summary = { "Pinch inside the vector scope to change it" },
            get = { prefs.vectorGain },
            set = { prefs.vectorGain = it }
        )

        addSlider(
            card, "Trace length", 2f, 200f, true, 20f,
            format = { String.format(java.util.Locale.US, "%.0f ms", it) },
            summary = { "Window of audio drawn in one pass" },
            get = { prefs.vectorTraceMs.toFloat() },
            set = { prefs.vectorTraceMs = it.roundToInt() }
        )

        addSwitch(
            card,
            "Show axes",
            { "Draws the L/R or M/S diagonals" },
            { prefs.vectorShowAxes },
            { prefs.vectorShowAxes = it }
        )
    }

    private fun buildMeters(root: LinearLayout) {
        val card = section(root, R.string.sec_meters)

        addSlider(
            card, "Peak hold time", 0f, 10000f, false, 1500f,
            format = { String.format(java.util.Locale.US, "%.0f ms", it) },
            summary = { "How long the hold marker stays before falling" },
            get = { prefs.meterHoldMs.toFloat() },
            set = { prefs.meterHoldMs = it.roundToInt() }
        )

        addChoice(
            card,
            "Layout",
            listOf("Split L/R", "Combined L+R", "Both"),
            summary = {
                when (prefs.meterLayout) {
                    1 -> "One bar for the summed left + right signal"
                    2 -> "Separate channels plus a summed bar"
                    else -> "One bar per channel"
                }
            },
            get = { prefs.meterLayout },
            set = { prefs.meterLayout = it }
        )

        addChoice(
            card,
            "Value placement",
            listOf("Under bars", "On bars"),
            summary = {
                if (prefs.meterLabelPosition == 1) {
                    "The value is printed across the middle of each bar"
                } else {
                    "A centred row of values under the bars"
                }
            },
            get = { prefs.meterLabelPosition },
            set = { prefs.meterLabelPosition = it }
        )

        addSwitch(
            card,
            "Slim bars",
            { "Keeps the bars narrow and centred so a slim window stays readable" },
            { prefs.meterSlim },
            { prefs.meterSlim = it }
        )

        addSwitch(
            card,
            "dB scale",
            { "Value labels along the meter, in both layouts" },
            { prefs.meterShowScale },
            { prefs.meterShowScale = it }
        )

        addSwitch(
            card,
            "Correlation meter",
            { "Shows phase correlation in the readout panel (both layouts)" },
            { prefs.meterShowCorrelation },
            { prefs.meterShowCorrelation = it }
        )

        addSwitch(
            card,
            "Loudness readout",
            { "Peak, RMS and crest factor in a dedicated panel" },
            { prefs.meterShowNumeric },
            { prefs.meterShowNumeric = it }
        )
    }

    private fun buildWindows(root: LinearLayout) {
        val card = section(root, R.string.sec_windows)

        addSlider(
            card, "Overlay opacity", 20f, 100f, false, 100f,
            format = { String.format(java.util.Locale.US, "%.0f %%", it) },
            summary = { "Transparency of the floating windows" },
            get = { prefs.overlayOpacity.toFloat() },
            set = { prefs.overlayOpacity = it.roundToInt() }
        )

        addSwitch(
            card,
            "Title bar",
            { "Drag the title bar to move a window, drag its edges to resize; without it the window is fixed" },
            { prefs.windowChrome },
            { prefs.windowChrome = it }
        )

        addSwitch(
            card,
            "Magnetic snapping",
            { "Snaps to screen edges, the centre and other windows" },
            { prefs.snapEnabled },
            { prefs.snapEnabled = it }
        )

        addSlider(
            card, "Snap distance", 0f, 48f, false, 16f,
            format = { String.format(java.util.Locale.US, "%.0f dp", it) },
            summary = { "How close an edge must be before it locks on" },
            get = { prefs.snapThresholdDp.toFloat() },
            set = { prefs.snapThresholdDp = it.roundToInt() }
        )

        addSwitch(
            card,
            "Match size when docked",
            { "Docked windows also share a size so scopes line up" },
            { prefs.snapMatchSize },
            { prefs.snapMatchSize = it }
        )

        addSwitch(
            card,
            "Link spectrum and waterfall",
            { "Pinching either tool zooms both and mirrors the cursors, keeping them column aligned" },
            { prefs.linkZoom },
            { prefs.linkZoom = it }
        )

        addChoice(
            card,
            "Render frame rate",
            listOf("15 fps", "30 fps", "60 fps"),
            summary = { "Lower values save battery on long sessions" },
            get = { if (prefs.renderFps <= 20) 0 else if (prefs.renderFps <= 45) 1 else 2 },
            set = { prefs.renderFps = listOf(15, 30, 60)[it] }
        )

        addSwitch(
            card,
            "Keep screen on",
            { "Prevents the display from sleeping while the activity is open" },
            { prefs.keepScreenOn },
            { prefs.keepScreenOn = it }
        )

        addSwitch(
            card,
            "Preview inside the app",
            { "Shows the enabled tools in this screen as a live preview" },
            { prefs.previewInApp },
            { prefs.previewInApp = it }
        )

        addSubHeader(card, "Touch lock")
        addInfo(
            card,
            { "Locked windows ignore touch so you can keep using the app underneath. Unlock them here or with \"Reset layout\" in the notification." }
        )
        for (tool in Tool.entries) {
            addSwitch(
                card,
                tool.title,
                { if (prefs.toolLocked(tool)) "Locked — taps pass through" else "Touchable" },
                { prefs.toolLocked(tool) },
                { prefs.setToolLocked(tool, it) }
            )
        }

        addButton(card, "Reset window layout") { actions.resetWindowLayout() }
    }

    private fun buildAppearance(root: LinearLayout) {
        val card = section(root, R.string.sec_appearance)

        addChoice(
            card,
            "Accent",
            ScopeTheme.accentNames,
            summary = { "Used for traces, cursors, meters, the window chrome and the notification" },
            get = { prefs.accentTheme },
            set = { prefs.accentTheme = it }
        )

        addChoice(
            card,
            "Background",
            ScopeTheme.backgroundNames,
            summary = {
                "${ScopeTheme.backgroundName(prefs.scopeBackground)} — applies to every scope surface and to this page"
            },
            get = { prefs.scopeBackground },
            set = { prefs.scopeBackground = it }
        )

        addChoice(
            card,
            "Scope backdrop",
            listOf("Plain", "Dots", "Vignette", "Scanlines"),
            summary = { "Texture drawn behind the traces" },
            get = { prefs.scopeBackdrop },
            set = { prefs.scopeBackdrop = it }
        )

        addSwitch(
            card,
            "Trace glow",
            { "Soft halo under every trace, for a lit-phosphor look" },
            { prefs.traceGlow },
            { prefs.traceGlow = it }
        )
    }

    // ------------------------------------------------------------------ row builders

    private fun section(root: LinearLayout, titleRes: Int): LinearLayout {
        val header = TextView(context)
        header.setTextAppearance(R.style.SectionHeader)
        header.setTextColor(accent)
        header.text = context.getString(titleRes).uppercase(java.util.Locale.US)
        root.addView(header, lpMatch())
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.card(context, palette)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))
        root.addView(card, lpMatch())
        // Sections are built once; the theme can change afterwards, so refresh their colours too.
        updaters.add {
            header.setTextColor(accent)
            card.background = Ui.card(context, palette)
        }
        return card
    }

    private fun addSubHeader(card: LinearLayout, text: String) {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(palette.text)
        tv.textSize = 14f
        tv.setTypeface(Typeface.DEFAULT_BOLD)
        tv.setPadding(0, dp(14), 0, dp(4))
        card.addView(tv, lpMatch())
    }

    private fun addInfo(card: LinearLayout, text: () -> String) {
        val tv = TextView(context)
        tv.setTextAppearance(R.style.RowSummary)
        Ui.styleSummary(tv, palette)
        card.addView(tv, lpMatch())
        updaters.add {
            tv.text = text()
            Ui.styleSummary(tv, palette)
        }
        tv.text = text()
    }

    private fun addSwitch(
        card: LinearLayout,
        title: String,
        summary: () -> String,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(48)
        row.setPadding(0, dp(6), 0, 0)

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        val titleView = TextView(context)
        titleView.setTextAppearance(R.style.RowTitle)
        titleView.text = title
        val summaryView = TextView(context)
        summaryView.setTextAppearance(R.style.RowSummary)
        Ui.styleTitle(titleView, palette)
        Ui.styleSummary(summaryView, palette)
        texts.addView(titleView, lpMatch())
        texts.addView(summaryView, lpMatch())
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = MaterialSwitch(context)
        toggle.isChecked = get()
        toggle.minimumHeight = dp(44)
        Ui.styleSwitch(toggle, palette)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            set(checked)
            toggle.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(row, lpMatch())
        updaters.add {
            toggle.isChecked = get()
            Ui.styleSwitch(toggle, palette)
            Ui.styleTitle(titleView, palette)
            Ui.styleSummary(summaryView, palette)
            summaryView.text = summary()
        }
        summaryView.text = summary()
    }

    private fun addChoice(
        card: LinearLayout,
        title: String,
        options: List<String>,
        summary: () -> String,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, 0)

        val titleView = TextView(context)
        titleView.setTextAppearance(R.style.RowTitle)
        titleView.text = title
        Ui.styleTitle(titleView, palette)
        row.addView(titleView, lpMatch())

        val summaryView = TextView(context)
        summaryView.setTextAppearance(R.style.RowSummary)
        Ui.styleSummary(summaryView, palette)
        row.addView(summaryView, lpMatch())

        val chips = LinearLayout(context)
        chips.orientation = LinearLayout.HORIZONTAL
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(
            chips,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        row.addView(scroll, lpMatch(top = 2))

        val chipViews = ArrayList<TextView>(options.size)
        fun refreshChips() {
            val selected = get().coerceIn(0, options.size - 1)
            chipViews.forEachIndexed { index, chip ->
                val on = index == selected
                chip.background = Ui.chip(context, palette, on)
                chip.setTextColor(Ui.chipTextColor(palette, on))
            }
        }
        options.forEachIndexed { index, labelText ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_chip, chips, false) as TextView
            chip.text = labelText
            chip.isClickable = true
            chip.setOnClickListener {
                if (suppress) return@setOnClickListener
                set(index)
                refreshChips()
                summaryView.text = summary()
                chip.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            chips.addView(chip)
            chipViews.add(chip)
        }
        card.addView(row, lpMatch())
        updaters.add {
            refreshChips()
            Ui.styleTitle(titleView, palette)
            Ui.styleSummary(summaryView, palette)
            summaryView.text = summary()
        }
        refreshChips()
        summaryView.text = summary()
    }

    private fun addSlider(
        card: LinearLayout,
        title: String,
        min: Float,
        max: Float,
        logarithmic: Boolean,
        defaultValue: Float,
        format: (Float) -> String,
        summary: () -> String,
        get: () -> Float,
        set: (Float) -> Unit
    ) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, 0)

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val titleView = TextView(context)
        titleView.setTextAppearance(R.style.RowTitle)
        titleView.text = title
        Ui.styleTitle(titleView, palette)
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Editable value: tap it to type an exact number.
        val valueView = EditText(context)
        valueView.setTextAppearance(R.style.RowTitle)
        valueView.setTextColor(accent)
        valueView.setBackgroundColor(0x00000000)
        valueView.setPadding(dp(4), dp(2), dp(4), dp(2))
        valueView.gravity = Gravity.END
        valueView.isSingleLine = true
        valueView.inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        valueView.imeOptions = EditorInfo.IME_ACTION_DONE
        head.addView(
            valueView,
            LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        row.addView(head, lpMatch())

        val summaryView = TextView(context)
        summaryView.setTextAppearance(R.style.RowSummary)
        Ui.styleSummary(summaryView, palette)
        row.addView(summaryView, lpMatch())

        val seek = SeekBar(context)
        seek.progressTintList = Ui.tint(accent)
        seek.thumbTintList = Ui.tint(accent)
        seek.max = SLIDER_STEPS
        seek.minimumHeight = dp(40)
        row.addView(seek, lpMatch(top = 2))

        fun valueToPos(value: Float): Int {
            val clamped = value.coerceIn(min, max)
            return if (logarithmic) {
                (((ln(clamped / min) / ln(max / min)) * SLIDER_STEPS).roundToInt())
            } else {
                (((clamped - min) / (max - min)) * SLIDER_STEPS).roundToInt()
            }.coerceIn(0, SLIDER_STEPS)
        }

        fun posToValue(pos: Int): Float {
            val t = pos.toFloat() / SLIDER_STEPS
            return if (logarithmic) min * (max / min).pow(t) else min + (max - min) * t
        }

        fun commitText() {
            val typed = NUMBER.find(valueView.text.toString())?.value?.toFloatOrNull()
            if (typed != null) {
                val clamped = typed.coerceIn(min, max)
                set(clamped)
                valueView.setText(format(clamped))
                seek.progress = valueToPos(clamped)
                summaryView.text = summary()
            } else {
                valueView.setText(format(get()))
            }
        }

        valueView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitText()
                valueView.clearFocus()
                true
            } else {
                false
            }
        }
        valueView.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitText() }

        // Double tap on the slider restores the default value. The rest of that gesture (including
        // the finger release) is swallowed, otherwise the release position would be committed as a
        // brand new value and defeat the reset.
        var swallowUntilUp = false
        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    swallowUntilUp = true
                    set(defaultValue)
                    seek.progress = valueToPos(defaultValue)
                    valueView.setText(format(defaultValue))
                    summaryView.text = summary()
                    Toast.makeText(context, "$title reset", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        )
        seek.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> swallowUntilUp = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (swallowUntilUp) {
                        swallowUntilUp = false
                        seek.progress = valueToPos(get())
                        return@setOnTouchListener true
                    }
                }
                else -> Unit
            }
            if (swallowUntilUp) return@setOnTouchListener true
            tapDetector.onTouchEvent(event)
            false
        }

        seek.progress = valueToPos(get())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppress) return
                val value = posToValue(progress)
                set(value)
                valueView.setText(format(value))
                summaryView.text = summary()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                bar?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        card.addView(row, lpMatch())
        updaters.add {
            val value = get()
            if (!valueView.hasFocus()) {
                seek.progress = valueToPos(value)
                valueView.setText(format(value))
            }
            Ui.styleTitle(titleView, palette)
            Ui.styleSummary(summaryView, palette)
            valueView.setTextColor(accent)
            seek.progressTintList = Ui.tint(accent)
            seek.thumbTintList = Ui.tint(accent)
            summaryView.text = summary()
        }
        valueView.setText(format(get()))
        summaryView.text = summary()
    }

    /** Two-handle control for the displayed dB window (floor .. ceiling). */
    private fun addDbRange(card: LinearLayout) {
        val title = TextView(context)
        title.setTextAppearance(R.style.RowTitle)
        title.text = "Display range"
        Ui.styleTitle(title, palette)
        card.addView(title, lpMatch(top = 10))

        val summary = TextView(context)
        summary.setTextAppearance(R.style.RowSummary)
        Ui.styleSummary(summary, palette)
        card.addView(summary, lpMatch())

        val slider = RangeSlider(context)
        slider.minValue = -180f
        slider.maxValue = 20f
        slider.step = 1f
        slider.setPalette(palette)
        slider.defaultLow = -100f
        slider.defaultHigh = 0f
        slider.setValues(prefs.dbFloor.toFloat(), prefs.dbTop.toFloat())
        slider.onRangeChanged = { low, high ->
            prefs.dbFloor = low.roundToInt()
            prefs.dbTop = high.roundToInt()
        }
        card.addView(slider, lpMatch())
        updaters.add {
            slider.setPalette(palette)
            slider.setValues(prefs.dbFloor.toFloat(), prefs.dbTop.toFloat())
            summary.text = String.format(
                java.util.Locale.US,
                "%d dBFS floor · %d dBFS ceiling — also drives the waterfall colours",
                prefs.dbFloor,
                prefs.dbTop
            )
            Ui.styleTitle(title, palette)
            Ui.styleSummary(summary, palette)
        }
    }

    private fun addButton(card: LinearLayout, title: String, action: () -> Unit) {
        val button = MaterialButton(context)
        button.text = title
        Ui.styleButton(button, palette, context)
        button.minimumHeight = dp(48)
        button.setOnClickListener {
            button.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
        card.addView(button, lpMatch(top = 12))
        updaters.add { Ui.styleButton(button, palette, context) }
    }

    // ------------------------------------------------------------------ helpers

    private fun lpMatch(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun hz(value: Int): String = when {
        value >= 1000 -> String.format(java.util.Locale.US, "%.1f kHz", value / 1000f)
        else -> "$value Hz"
    }

    companion object {
        private const val SLIDER_STEPS = 1000
        private val NUMBER = Regex("[-+]?[0-9]*\\.?[0-9]+")
    }
}

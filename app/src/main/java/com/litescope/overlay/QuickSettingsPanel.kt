package com.litescope.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.litescope.R
import com.litescope.core.Prefs
import com.litescope.core.Tool
import com.litescope.ui.RangeSlider
import com.litescope.view.Palette
import com.litescope.view.ScopeTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Floating quick-settings panel for one tool, opened from the window's gear button.
 *
 * It shows only that tool's own controls, styled with the scope palette (the same colours as the
 * floating windows, not the Material theme), and needs nothing but a close button. The panel lives
 * in its own overlay window so it can extend past the scope it belongs to.
 */
class QuickSettingsPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: Prefs
) {

    private var params: WindowManager.LayoutParams? = null

    /** Rows that re-read their value from the prefs, refreshed while the panel is open. */
    private val refreshable = ArrayList<() -> Unit>()

    private val prefsListener = object : Prefs.Listener {
        override fun onPrefsChanged(keys: Set<String>) {
            if (isShowing) refreshable.forEach { it() }
        }
    }
    private var root: View? = null
    private var tool: Tool? = null

    val isShowing: Boolean get() = root != null

    fun dismiss() {
        val view = root ?: return
        prefs.removeListener(prefsListener)
        refreshable.clear()
        root = null
        tool = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // already detached
        }
    }

    fun toggle(tool: Tool, screen: Point, anchorY: Int) {
        if (this.tool == tool && isShowing) {
            dismiss()
            return
        }
        dismiss()
        show(tool, screen, anchorY)
    }

    private fun show(tool: Tool, screen: Point, anchorY: Int) {
        val palette = ScopeTheme.palette(prefs)
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        // Layered background: a wide faint accent ring (glow) plus the crisp hairline outline.
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f)
            setColor(palette.alpha(palette.bg, 0.97f))
            setStroke(dp(1f).toInt().coerceAtLeast(1), palette.alpha(palette.accent, 0.55f))
        }
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f)
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(5f).toInt(), palette.alpha(palette.accent, 0.16f))
        }
        column.background = android.graphics.drawable.LayerDrawable(arrayOf(glow, inner))
        column.setPadding(dpInt(14), dpInt(8), dpInt(14), dpInt(12))
        column.setPadding(dpInt(12), dpInt(8), dpInt(12), dpInt(12))

        column.addView(buildHeader(tool, palette))

        val body = LinearLayout(context)
        body.orientation = LinearLayout.VERTICAL
        fillBody(body, tool, palette)

        val scroller = ScrollView(context)
        scroller.isVerticalScrollBarEnabled = false
        scroller.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val width = (screen.x * 0.72f).toInt().coerceIn(dpInt(240), dpInt(380))
        val height = (screen.y * 0.5f).toInt().coerceAtLeast(dpInt(260))
        val p = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screen.x - width) / 2).coerceAtLeast(0)
            y = anchorY.coerceIn(dpInt(40), (screen.y - height - dpInt(20)).coerceAtLeast(dpInt(40)))
            setTitle("LiteScope ${tool.title} settings")
        }
        try {
            windowManager.addView(column, p)
            root = column
            this.tool = tool
            params = p
            prefs.addListener(prefsListener)
        } catch (t: Throwable) {
            root = null
        }
    }

    private fun buildHeader(tool: Tool, palette: Palette): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context)
        title.text = tool.title
        title.setTextColor(palette.accent)
        title.textSize = 13f
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageView(context)
        close.setImageResource(R.drawable.ic_close)
        close.setColorFilter(palette.dim)
        close.setPadding(dpInt(6), dpInt(6), dpInt(6), dpInt(6))
        close.isClickable = true
        close.setOnClickListener { dismiss() }
        row.addView(close, LinearLayout.LayoutParams(dpInt(32), dpInt(32)))
        return row
    }

    // ------------------------------------------------------------------ row builders

    private fun fillBody(body: LinearLayout, tool: Tool, palette: Palette) {
        when (tool) {
            Tool.SPECTRUM -> {
                choice(body, "FFT size", Prefs.FFT_SIZE_OPTIONS.map { it.toString() },
                    { Prefs.FFT_SIZE_OPTIONS.indexOf(prefs.fftSize).coerceAtLeast(0) },
                    { prefs.fftSize = Prefs.FFT_SIZE_OPTIONS[it] }, palette)
                choice(body, "Window", listOf("Hann", "Hamming", "B-H", "Flat", "Rect"),
                    { windowIndex() }, { prefs.windowType = WINDOW_KEYS[it] }, palette)
                choice(body, "Overlap", listOf("0%", "50%", "75%"),
                    { prefs.fftOverlap }, { prefs.fftOverlap = it }, palette)
                slider(body, "Averaging", 0f, 0.95f, false, 0.55f,
                    { prefs.spectrumAveraging }, { prefs.spectrumAveraging = it },
                    { String.format(java.util.Locale.US, "%.2f", it) }, palette)
                slider(body, "Log scale", 0f, 1f, false, 1f,
                    { prefs.freqScaleBlend }, { prefs.freqScaleBlend = it },
                    { String.format(java.util.Locale.US, "%.2f", it) }, palette)
                slider(body, "Peak decay", 0f, 60f, false, 14f,
                    { prefs.peakDecayDbPerSec }, { prefs.peakDecayDbPerSec = it },
                    { String.format(java.util.Locale.US, "%.0f dB/s", it) }, palette)
                toggle(body, "Peak hold", { prefs.peakHold }, { prefs.peakHold = it }, palette)
                toggle(body, "Pink tilt", { prefs.spectrumTilt }, { prefs.spectrumTilt = it }, palette)
                toggle(body, "Readout", { prefs.showReadout }, { prefs.showReadout = it }, palette)
                toggle(body, "Cursor note", { prefs.cursorNoteEnabled }, { prefs.cursorNoteEnabled = it }, palette)
                slider(body, "Tuning A4", 400f, 480f, false, 440f,
                    { prefs.tuningHz.toFloat() }, { prefs.tuningHz = it.roundToInt() },
                    { String.format(java.util.Locale.US, "%.0f Hz", it) }, palette)
                dbRange(body, palette)
                action(body, "Clear cursors", palette) {
                    com.litescope.core.ScopeHub.get(context).cursors.clear()
                    dismiss()
                }
                hint(body, "Tap adds a cursor · drag moves it · long press locks", palette)
            }
            Tool.WATERFALL -> {
                slider(body, "History", 2f, 300f, true, 12f,
                    { prefs.waterfallHistorySec }, { prefs.waterfallHistorySec = it },
                    { String.format(java.util.Locale.US, "%.0f s", it) }, palette)
                choice(body, "Colour", listOf("Magma", "Viridis", "Inferno", "Fire", "Ice", "Rainbow", "Gray"),
                    { prefs.waterfallColormap }, { prefs.waterfallColormap = it }, palette)
                slider(body, "Row rate", 5f, 60f, false, 24f,
                    { prefs.waterfallFps.toFloat() }, { prefs.waterfallFps = it.roundToInt() },
                    { String.format(java.util.Locale.US, "%.0f /s", it) }, palette)
                toggle(body, "Newest on top", { prefs.waterfallNewestOnTop },
                    { prefs.waterfallNewestOnTop = it }, palette)
                toggle(body, "Freeze", { prefs.waterfallFreeze }, { prefs.waterfallFreeze = it }, palette)
                toggle(body, "Link to spectrum", { prefs.linkZoom }, { prefs.linkZoom = it }, palette)
                hint(body, "Tap freezes · long press clears history", palette)
            }
            Tool.WAVEFORM -> {
                choice(body, "Stereo", listOf("Overlay", "Split", "Sum"),
                    { prefs.waveStereoMode }, { prefs.waveStereoMode = it }, palette)
                slider(body, "Time base", 0.02f, 200f, true, 5f,
                    { prefs.waveMsPerDiv }, { prefs.waveMsPerDiv = it },
                    { String.format(java.util.Locale.US, "%.2f ms", it) }, palette)
                slider(body, "Gain L", -40f, 60f, false, 0f,
                    { prefs.waveGainDbL },
                    {
                        prefs.waveGainDbL = it
                        if (prefs.waveLinkGain) prefs.waveGainDbR = it
                    },
                    { String.format(java.util.Locale.US, "%+.1f dB", it) }, palette)
                slider(body, "Gain R", -40f, 60f, false, 0f,
                    { prefs.waveGainDbR },
                    {
                        prefs.waveGainDbR = it
                        if (prefs.waveLinkGain) prefs.waveGainDbL = it
                    },
                    { String.format(java.util.Locale.US, "%+.1f dB", it) }, palette)
                choice(body, "Trigger", listOf("Auto", "Normal", "Free"),
                    { prefs.waveTriggerMode }, { prefs.waveTriggerMode = it }, palette)
                slider(body, "Trig level", -1f, 1f, false, 0f,
                    { prefs.waveTriggerLevel }, { prefs.waveTriggerLevel = it },
                    { String.format(java.util.Locale.US, "%+.2f", it) }, palette)
                toggle(body, "Link L/R gain", { prefs.waveLinkGain }, { prefs.waveLinkGain = it }, palette)
                toggle(body, "AC coupling", { prefs.waveAcCoupling }, { prefs.waveAcCoupling = it }, palette)
                toggle(body, "Measurements", { prefs.waveShowMeasurements },
                    { prefs.waveShowMeasurements = it }, palette)
                hint(body, "Tap freezes · pinch zooms · drag pans", palette)
            }
            Tool.VECTOR -> {
                choice(body, "Mode", listOf("Lissajous", "Goniometer"),
                    { prefs.vectorMode }, { prefs.vectorMode = it }, palette)
                slider(body, "Persistence", 0f, 0.97f, false, 0.72f,
                    { prefs.vectorPersistence }, { prefs.vectorPersistence = it },
                    { String.format(java.util.Locale.US, "%.2f", it) }, palette)
                slider(body, "Gain", 0.05f, 8f, true, 1f,
                    { prefs.vectorGain }, { prefs.vectorGain = it },
                    { String.format(java.util.Locale.US, "%.2f x", it) }, palette)
                slider(body, "Trace", 2f, 200f, true, 20f,
                    { prefs.vectorTraceMs.toFloat() }, { prefs.vectorTraceMs = it.roundToInt() },
                    { String.format(java.util.Locale.US, "%.0f ms", it) }, palette)
                toggle(body, "Axes", { prefs.vectorShowAxes }, { prefs.vectorShowAxes = it }, palette)
            }
            Tool.METERS -> {
                choice(body, "Channels", listOf("Split L/R", "Combined", "Both"),
                    { prefs.meterLayout }, { prefs.meterLayout = it }, palette)
                choice(body, "Values", listOf("Under bars", "On bars"),
                    { prefs.meterLabelPosition }, { prefs.meterLabelPosition = it }, palette)
                slider(body, "Hold", 0f, 10000f, false, 1500f,
                    { prefs.meterHoldMs.toFloat() }, { prefs.meterHoldMs = it.roundToInt() },
                    { String.format(java.util.Locale.US, "%.0f ms", it) }, palette)
                toggle(body, "Slim bars", { prefs.meterSlim }, { prefs.meterSlim = it }, palette)
                toggle(body, "dB scale", { prefs.meterShowScale }, { prefs.meterShowScale = it }, palette)
                toggle(body, "Correlation", { prefs.meterShowCorrelation },
                    { prefs.meterShowCorrelation = it }, palette)
                toggle(body, "Loudness readout", { prefs.meterShowNumeric },
                    { prefs.meterShowNumeric = it }, palette)
            }
        }
    }

    private fun dbRange(body: LinearLayout, palette: Palette) {
        val title = label("Display range (dBFS)", palette)
        body.addView(title)
        val slider = RangeSlider(context)
        slider.minValue = -180f
        slider.maxValue = 20f
        slider.step = 1f
        slider.defaultLow = -100f
        slider.defaultHigh = 0f
        slider.setPalette(palette)
        slider.setValues(prefs.dbFloor.toFloat(), prefs.dbTop.toFloat())
        slider.onRangeChanged = { low, high ->
            prefs.dbFloor = low.roundToInt()
            prefs.dbTop = high.roundToInt()
        }
        body.addView(slider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun hint(body: LinearLayout, text: String, palette: Palette) {
        val view = TextView(context)
        view.text = text
        view.setTextColor(palette.alpha(palette.dim, 0.85f))
        view.textSize = 10f
        view.setPadding(0, dpInt(8), 0, 0)
        body.addView(view)
    }

    private fun label(text: String, palette: Palette): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextColor(palette.dim)
        view.textSize = 11f
        view.setPadding(0, dpInt(8), 0, dpInt(2))
        return view
    }

    private fun action(body: LinearLayout, title: String, palette: Palette, onClick: () -> Unit) {
        val view = TextView(context)
        view.text = title
        view.setTextColor(palette.accent)
        view.textSize = 12f
        view.gravity = Gravity.CENTER
        view.setPadding(0, dpInt(10), 0, dpInt(10))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            setColor(palette.alpha(palette.accent, 0.14f))
            setStroke(dp(1f).toInt().coerceAtLeast(1), palette.alpha(palette.accent, 0.4f))
        }
        view.isClickable = true
        view.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dpInt(10)
        body.addView(view, lp)
    }

    private fun toggle(
        body: LinearLayout,
        title: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        palette: Palette
    ) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dpInt(6), 0, 0)
        val text = TextView(context)
        text.text = title
        text.setTextColor(palette.text)
        text.textSize = 12f
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val box = TextView(context)
        box.text = if (get()) "ON" else "OFF"
        box.textSize = 11f
        box.gravity = Gravity.CENTER
        box.setTextColor(if (get()) palette.bg else palette.dim)
        box.background = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(if (get()) palette.accent else palette.alpha(palette.text, 0.12f))
        }
        box.setPadding(dpInt(12), dpInt(5), dpInt(12), dpInt(5))
        box.isClickable = true
        box.setOnClickListener {
            box.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            val next = !get()
            set(next)
            box.text = if (next) "ON" else "OFF"
            box.setTextColor(if (next) palette.bg else palette.dim)
            (box.background as GradientDrawable).setColor(
                if (next) palette.accent else palette.alpha(palette.text, 0.12f)
            )
        }
        row.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        body.addView(row)
    }

    private fun choice(
        body: LinearLayout,
        title: String,
        options: List<String>,
        get: () -> Int,
        set: (Int) -> Unit,
        palette: Palette
    ) {
        body.addView(label(title, palette))
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<TextView>(options.size)
        fun refresh() {
            val selected = get().coerceIn(0, options.size - 1)
            chips.forEachIndexed { index, chip ->
                val on = index == selected
                (chip.background as GradientDrawable).setColor(
                    if (on) palette.accent else palette.alpha(palette.text, 0.10f)
                )
                chip.setTextColor(if (on) palette.bg else palette.dim)
            }
        }
        options.forEachIndexed { index, text ->
            val chip = TextView(context)
            chip.text = text
            chip.textSize = 11f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dpInt(10), dpInt(6), dpInt(10), dpInt(6))
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(9f)
                setColor(palette.alpha(palette.text, 0.10f))
            }
            chip.isClickable = true
            chip.setOnClickListener {
                set(index)
                refresh()
                chip.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dpInt(6)
            row.addView(chip, lp)
            chips.add(chip)
        }
        refresh()
        val scroller = HorizontalScrollView(context)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(row)
        body.addView(scroller)
    }

    private fun slider(
        body: LinearLayout,
        title: String,
        min: Float,
        max: Float,
        logarithmic: Boolean,
        default: Float,
        get: () -> Float,
        set: (Float) -> Unit,
        format: (Float) -> String,
        palette: Palette
    ) {
        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.setPadding(0, dpInt(8), 0, 0)
        val text = TextView(context)
        text.text = title
        text.setTextColor(palette.dim)
        text.textSize = 11f
        head.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(context)
        value.text = format(get())
        value.setTextColor(palette.accent)
        value.textSize = 11f
        head.addView(value)
        body.addView(head)

        val seek = SeekBar(context)
        seek.max = 1000
        seek.progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)

        fun toPos(v: Float): Int = if (logarithmic) {
            (((ln(v.coerceIn(min, max) / min) / ln(max / min)) * 1000).roundToInt())
        } else {
            (((v.coerceIn(min, max) - min) / (max - min)) * 1000).roundToInt()
        }.coerceIn(0, 1000)

        fun toValue(pos: Int): Float {
            val t = pos / 1000f
            return if (logarithmic) min * (max / min).pow(t) else min + (max - min) * t
        }

        seek.progress = toPos(get())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = toValue(progress)
                set(v)
                value.text = format(v)
                if (progress % 25 == 0) bar?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dpInt(4)
        body.addView(seek, lp)
        // Keep the row in sync when the value is changed elsewhere (e.g. by a scope drag).
        refreshable += {
            val current = get()
            val pos = toPos(current)
            if (seek.progress != pos && !seek.isPressed) seek.progress = pos
            value.text = format(current)
        }
        seek.setOnLongClickListener {
            set(default)
            seek.progress = toPos(default)
            value.text = format(default)
            true
        }
    }

    private fun windowIndex(): Int = when (prefs.windowType) {
        "HAMMING" -> 1
        "BLACKMAN_HARRIS" -> 2
        "FLAT_TOP" -> 3
        "RECTANGULAR" -> 4
        else -> 0
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private fun dpInt(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private val WINDOW_KEYS = arrayOf(
            "HANN", "HAMMING", "BLACKMAN_HARRIS", "FLAT_TOP", "RECTANGULAR"
        )

    }
}

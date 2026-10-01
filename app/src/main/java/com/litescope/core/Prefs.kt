package com.litescope.core

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap

/**
 * Single source of truth for every user-tunable value in LiteScope.
 *
 * Writes are cheap in-memory updates (`apply()`), bump [generation] and notify registered
 * listeners so the live scope views and the analysis thread can re-read their configuration
 * without polling. Listeners are held weakly, so views never have to be unregistered to avoid
 * leaks (they still should be, for clarity).
 */
class Prefs private constructor(context: Context) {

    interface Listener {
        fun onPrefsChanged(keys: Set<String>)
    }

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("litescope", Context.MODE_PRIVATE)

    private val listeners: MutableMap<Listener, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Listener, Boolean>())

    /** Incremented on every write; the analysis thread uses it to detect config changes. */
    @Volatile
    var generation: Int = 0
        private set

    fun addListener(listener: Listener) {
        listeners[listener] = true
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun changed(vararg keys: String) {
        generation++
        val set: Set<String> = if (keys.size == 1) setOf(keys[0]) else keys.toSet()
        val snapshot: List<Listener>
        synchronized(listeners) { snapshot = listeners.keys.toList() }
        for (l in snapshot) l.onPrefsChanged(set)
    }

    // ---------------------------------------------------------------- primitives

    fun int(key: String, def: Int): Int = sp.getInt(key, def)
    fun setInt(key: String, value: Int) {
        if (sp.getInt(key, Int.MIN_VALUE) != value) {
            sp.edit().putInt(key, value).apply()
            changed(key)
        }
    }

    fun long(key: String, def: Long): Long = sp.getLong(key, def)
    fun setLong(key: String, value: Long) {
        if (sp.getLong(key, Long.MIN_VALUE) != value) {
            sp.edit().putLong(key, value).apply()
            changed(key)
        }
    }

    fun float(key: String, def: Float): Float = sp.getFloat(key, def)
    fun setFloat(key: String, value: Float) {
        if (sp.getFloat(key, Float.MIN_VALUE) != value) {
            sp.edit().putFloat(key, value).apply()
            changed(key)
        }
    }

    fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun setBool(key: String, value: Boolean) {
        if (sp.getBoolean(key, !value) != value) {
            sp.edit().putBoolean(key, value).apply()
            changed(key)
        }
    }

    fun string(key: String, def: String?): String? = sp.getString(key, def)
    fun setString(key: String, value: String) {
        if (sp.getString(key, null) != value) {
            sp.edit().putString(key, value).apply()
            changed(key)
        }
    }

    fun clearAll() {
        sp.edit().clear().apply()
        changed(*KEYS.toTypedArray())
    }

    // ---------------------------------------------------------------- capture

    /** Requested capture rate; [RATE_AUTO] asks the platform for the device's native rate. */
    var captureRate: Int
        get() = int(KEY_CAPTURE_RATE, RATE_AUTO)
        set(v) = setInt(KEY_CAPTURE_RATE, v)

    /** Integer decimation factor applied before the FFT ("analysis sample rate" selection). */
    var analysisDivisor: Int
        get() = int(KEY_ANALYSIS_DIV, 1).coerceAtLeast(1)
        set(v) = setInt(KEY_ANALYSIS_DIV, v.coerceAtLeast(1))

    /** Spectrum input channel: 0 = L+R sum, 1 = L, 2 = R. */
    var spectrumChannel: Int
        get() = int(KEY_SPECTRUM_CHANNEL, 0)
        set(v) = setInt(KEY_SPECTRUM_CHANNEL, v)

    fun analysisRate(captureRate: Int): Int = (captureRate / analysisDivisor).coerceAtLeast(1000)

    /** Built-in signal generator: inspects the analyser without any app playing audio. */
    var testSignal: Boolean
        get() = bool(KEY_TEST_SIGNAL, false)
        set(v) = setBool(KEY_TEST_SIGNAL, v)

    /** Index into `SignalGenerator.Signal`. */
    var testSignalType: Int
        get() = int(KEY_TEST_SIGNAL_TYPE, 0)
        set(v) = setInt(KEY_TEST_SIGNAL_TYPE, v)

    var testSignalLevel: Float
        get() = float(KEY_TEST_SIGNAL_LEVEL, 0.5f).coerceIn(0f, 1f)
        set(v) = setFloat(KEY_TEST_SIGNAL_LEVEL, v)

    // ---------------------------------------------------------------- spectrum

    var fftSize: Int
        get() = int(KEY_FFT_SIZE, 2048).coerceIn(256, 8192)
        set(v) = setInt(KEY_FFT_SIZE, v.coerceIn(256, 8192))

    var windowType: String
        get() = string(KEY_WINDOW, "HANN") ?: "HANN"
        set(v) = setString(KEY_WINDOW, v)

    /** 0 = no overlap, 1 = 50 %, 2 = 75 %. */
    var fftOverlap: Int
        get() = int(KEY_FFT_OVERLAP, 1).coerceIn(0, 2)
        set(v) = setInt(KEY_FFT_OVERLAP, v)

    /** Exponential averaging coefficient, 0 = raw frame, 0.95 = very slow. */
    var spectrumAveraging: Float
        get() = float(KEY_AVG, 0.55f).coerceIn(0f, 0.95f)
        set(v) = setFloat(KEY_AVG, v)

    var spectrumTilt: Boolean
        get() = bool(KEY_TILT, false)
        set(v) = setBool(KEY_TILT, v)

    var tiltDbPerOctave: Float
        get() = float(KEY_TILT_DB_OCT, 3f)
        set(v) = setFloat(KEY_TILT_DB_OCT, v)

    var dbFloor: Int
        get() = int(KEY_DB_FLOOR, -100).coerceIn(-140, -20)
        set(v) = setInt(KEY_DB_FLOOR, v)

    /** Top of the dB scale in dBFS (0 = full scale); the floor is [dbFloor]. */
    var dbTop: Int
        get() = int(KEY_DB_TOP, 0).coerceIn(-60, 20)
        set(v) = setInt(KEY_DB_TOP, v)

    var peakHold: Boolean
        get() = bool(KEY_PEAK_HOLD, true)
        set(v) = setBool(KEY_PEAK_HOLD, v)

    var peakDecayDbPerSec: Float
        get() = float(KEY_PEAK_DECAY, 14f)
        set(v) = setFloat(KEY_PEAK_DECAY, v)

    /** 0 = pure linear frequency axis, 1 = pure logarithmic; anything in between blends. */
    var freqScaleBlend: Float
        get() = float(KEY_FREQ_BLEND, 1f).coerceIn(0f, 1f)
        set(v) = setFloat(KEY_FREQ_BLEND, v.coerceIn(0f, 1f))

    /** Legacy helper kept for the summary strings: true when the axis is essentially logarithmic. */
    val logFrequencyAxis: Boolean get() = freqScaleBlend >= 0.999f

    /** Shows the equivalent musical note next to every measurement cursor. */
    var cursorNoteEnabled: Boolean
        get() = bool(KEY_CURSOR_NOTE, true)
        set(v) = setBool(KEY_CURSOR_NOTE, v)

    /** Frequency of A4 used for the note readout, 440 Hz by default. */
    var tuningHz: Int
        get() = int(KEY_TUNING, 440).coerceIn(400, 480)
        set(v) = setInt(KEY_TUNING, v)

    var showReadout: Boolean
        get() = bool(KEY_SHOW_READOUT, true)
        set(v) = setBool(KEY_SHOW_READOUT, v)

    // ---------------------------------------------------------------- waterfall

    /** Requested waterfall history in seconds. Rows are thinned to fit the pixel budget. */
    var waterfallHistorySec: Float
        get() = float(KEY_WF_HISTORY, 12f).coerceIn(1f, 300f)
        set(v) = setFloat(KEY_WF_HISTORY, v)

    var waterfallColormap: Int
        get() = int(KEY_WF_COLORMAP, 0)
        set(v) = setInt(KEY_WF_COLORMAP, v)

    var waterfallNewestOnTop: Boolean
        get() = bool(KEY_WF_NEWEST_TOP, true)
        set(v) = setBool(KEY_WF_NEWEST_TOP, v)

    var waterfallFps: Int
        get() = int(KEY_WF_FPS, 24).coerceIn(5, 60)
        set(v) = setInt(KEY_WF_FPS, v)

    var waterfallFreeze: Boolean
        get() = bool(KEY_WF_FREEZE, false)
        set(v) = setBool(KEY_WF_FREEZE, v)

    // ---------------------------------------------------------------- waveform

    /** 0 = combined (L/R overlaid), 1 = individual (split lanes). */
    var waveStereoMode: Int
        get() = int(KEY_WAVE_MODE, 0)
        set(v) = setInt(KEY_WAVE_MODE, v)

    /** Horizontal time base in milliseconds per division (10 divisions across the view). */
    var waveMsPerDiv: Float
        get() = float(KEY_WAVE_MS_DIV, 5f).coerceIn(0.02f, 200f)
        set(v) = setFloat(KEY_WAVE_MS_DIV, v)

    var waveGainDbL: Float
        get() = float(KEY_WAVE_GAIN_L, 0f).coerceIn(-40f, 60f)
        set(v) = setFloat(KEY_WAVE_GAIN_L, v)

    var waveGainDbR: Float
        get() = float(KEY_WAVE_GAIN_R, 0f).coerceIn(-40f, 60f)
        set(v) = setFloat(KEY_WAVE_GAIN_R, v)

    /** When true a gain change (slider or drag) applies to both channels at once. */
    var waveLinkGain: Boolean
        get() = bool(KEY_WAVE_LINK_GAIN, true)
        set(v) = setBool(KEY_WAVE_LINK_GAIN, v)

    /** 0 = auto, 1 = normal, 2 = free running. */
    var waveTriggerMode: Int
        get() = int(KEY_WAVE_TRIG_MODE, 0)
        set(v) = setInt(KEY_WAVE_TRIG_MODE, v)

    /** Trigger level in full-scale units, -1..1. */
    var waveTriggerLevel: Float
        get() = float(KEY_WAVE_TRIG_LEVEL, 0f).coerceIn(-1f, 1f)
        set(v) = setFloat(KEY_WAVE_TRIG_LEVEL, v)

    /** 0 = rising edge, 1 = falling edge. */
    var waveTriggerEdge: Int
        get() = int(KEY_WAVE_TRIG_EDGE, 0)
        set(v) = setInt(KEY_WAVE_TRIG_EDGE, v)

    /** 0 = L+R sum, 1 = L, 2 = R. */
    var waveTriggerSource: Int
        get() = int(KEY_WAVE_TRIG_SOURCE, 0)
        set(v) = setInt(KEY_WAVE_TRIG_SOURCE, v)

    /** 0 = L/R overlay, 1 = L, 2 = R, 3 = mid, 4 = side. */
    var waveSource: Int
        get() = int(KEY_WAVE_SOURCE, 0)
        set(v) = setInt(KEY_WAVE_SOURCE, v)

    var waveAcCoupling: Boolean
        get() = bool(KEY_WAVE_AC, true)
        set(v) = setBool(KEY_WAVE_AC, v)

    var waveShowMeasurements: Boolean
        get() = bool(KEY_WAVE_MEASURE, true)
        set(v) = setBool(KEY_WAVE_MEASURE, v)

    var waveDots: Boolean
        get() = bool(KEY_WAVE_DOTS, false)
        set(v) = setBool(KEY_WAVE_DOTS, v)

    // ---------------------------------------------------------------- vector scope

    /** 0 = Lissajous (X = L, Y = R), 1 = goniometer (rotated 45°, M/S). */
    var vectorMode: Int
        get() = int(KEY_VEC_MODE, 0)
        set(v) = setInt(KEY_VEC_MODE, v)

    var vectorPersistence: Float
        get() = float(KEY_VEC_PERSIST, 0.72f).coerceIn(0f, 0.97f)
        set(v) = setFloat(KEY_VEC_PERSIST, v)

    var vectorGain: Float
        get() = float(KEY_VEC_GAIN, 1f).coerceIn(0.05f, 8f)
        set(v) = setFloat(KEY_VEC_GAIN, v)

    var vectorShowAxes: Boolean
        get() = bool(KEY_VEC_AXES, true)
        set(v) = setBool(KEY_VEC_AXES, v)

    var vectorTraceMs: Int
        get() = int(KEY_VEC_TRACE_MS, 20).coerceIn(2, 200)
        set(v) = setInt(KEY_VEC_TRACE_MS, v)

    // ---------------------------------------------------------------- meters

    var meterHoldMs: Int
        get() = int(KEY_METER_HOLD_MS, 1500).coerceIn(0, 10000)
        set(v) = setInt(KEY_METER_HOLD_MS, v)

    var meterShowCorrelation: Boolean
        get() = bool(KEY_METER_CORR, true)
        set(v) = setBool(KEY_METER_CORR, v)

    var meterShowNumeric: Boolean
        get() = bool(KEY_METER_NUMERIC, true)
        set(v) = setBool(KEY_METER_NUMERIC, v)

    // ---------------------------------------------------------------- overlay windows

    var overlayOpacity: Int
        get() = int(KEY_OVERLAY_OPACITY, 100).coerceIn(20, 100)
        set(v) = setInt(KEY_OVERLAY_OPACITY, v)

    var windowChrome: Boolean
        get() = bool(KEY_WIN_CHROME, true)
        set(v) = setBool(KEY_WIN_CHROME, v)

    var snapEnabled: Boolean
        get() = bool(KEY_WIN_SNAP, true)
        set(v) = setBool(KEY_WIN_SNAP, v)

    var snapThresholdDp: Int
        get() = int(KEY_WIN_SNAP_DP, 16).coerceIn(0, 48)
        set(v) = setInt(KEY_WIN_SNAP_DP, v)

    var snapMatchSize: Boolean
        get() = bool(KEY_WIN_MATCH_SIZE, true)
        set(v) = setBool(KEY_WIN_MATCH_SIZE, v)

    var linkZoom: Boolean
        get() = bool(KEY_LINK_ZOOM, true)
        set(v) = setBool(KEY_LINK_ZOOM, v)

    var renderFps: Int
        get() = int(KEY_FPS, 30).coerceIn(5, 60)
        set(v) = setInt(KEY_FPS, v)

    var keepScreenOn: Boolean
        get() = bool(KEY_KEEP_SCREEN_ON, false)
        set(v) = setBool(KEY_KEEP_SCREEN_ON, v)

    var previewInApp: Boolean
        get() = bool(KEY_PREVIEW, true)
        set(v) = setBool(KEY_PREVIEW, v)

    /**
     * Accent palette index. The authoritative list (and its length) lives in `ScopeTheme`; clamping
     * here to a hard-coded count is what silently turned Rose/Lime/Ember into Mono.
     */
    var accentTheme: Int
        get() = int(KEY_ACCENT, 0).coerceIn(0, THEME_INDEX_LIMIT)
        set(v) = setInt(KEY_ACCENT, v)

    /** Scope background index; see [accentTheme] about the upper bound. */
    var scopeBackground: Int
        get() = int(KEY_SCOPE_BG, 0).coerceIn(0, THEME_INDEX_LIMIT)
        set(v) = setInt(KEY_SCOPE_BG, v)

    /** Scope backdrop: 0 plain, 1 dot matrix, 2 vignette, 3 scanlines. */
    var scopeBackdrop: Int
        get() = int(KEY_BACKDROP, 0).coerceIn(0, 3)
        set(v) = setInt(KEY_BACKDROP, v)

    /** Draws a soft glow under every trace. */
    var traceGlow: Boolean
        get() = bool(KEY_GLOW, true)
        set(v) = setBool(KEY_GLOW, v)

    /** Meters layout: 0 = split L/R, 1 = combined L+R, 2 = both. */
    var meterLayout: Int
        get() = int(KEY_METER_LAYOUT, 0).coerceIn(0, 2)
        set(v) = setInt(KEY_METER_LAYOUT, v)

    /** Where the per-channel value text goes: 0 under the bars, 1 on the bars. */
    var meterLabelPosition: Int
        get() = int(KEY_METER_LABELS, 0).coerceIn(0, 1)
        set(v) = setInt(KEY_METER_LABELS, v)

    /** Keeps the meter bars narrow and centred instead of filling the window. */
    var meterSlim: Boolean
        get() = bool(KEY_METER_SLIM, true)
        set(v) = setBool(KEY_METER_SLIM, v)

    /** Scale labels drawn along the meter. */
    var meterShowScale: Boolean
        get() = bool(KEY_METER_SCALE, true)
        set(v) = setBool(KEY_METER_SCALE, v)

    fun toolVisible(tool: Tool): Boolean = bool(KEY_TOOL_PREFIX + tool.id, false)
    fun setToolVisible(tool: Tool, visible: Boolean) = setBool(KEY_TOOL_PREFIX + tool.id, visible)

    /** Locked windows ignore touch entirely (clicks pass through to the app underneath). */
    fun toolLocked(tool: Tool): Boolean = bool(KEY_LOCK_PREFIX + tool.id, false)
    fun setToolLocked(tool: Tool, locked: Boolean) = setBool(KEY_LOCK_PREFIX + tool.id, locked)

    fun geometry(tool: Tool): WindowGeometry {
        val def = tool.defaultGeometry()
        return WindowGeometry(
            int(KEY_GEOM_PREFIX + tool.id + "_x", def.x),
            int(KEY_GEOM_PREFIX + tool.id + "_y", def.y),
            int(KEY_GEOM_PREFIX + tool.id + "_w", def.w),
            int(KEY_GEOM_PREFIX + tool.id + "_h", def.h)
        )
    }

    fun setGeometry(tool: Tool, geometry: WindowGeometry) {
        sp.edit()
            .putInt(KEY_GEOM_PREFIX + tool.id + "_x", geometry.x)
            .putInt(KEY_GEOM_PREFIX + tool.id + "_y", geometry.y)
            .putInt(KEY_GEOM_PREFIX + tool.id + "_w", geometry.w)
            .putInt(KEY_GEOM_PREFIX + tool.id + "_h", geometry.h)
            .apply()
        changed(KEY_GEOM_PREFIX + tool.id)
    }

    // ---------------------------------------------------------------- derived helpers

    /** Decimation factors that divide [captureRate] exactly, paired with the resulting rate. */
    fun divisorOptions(captureRate: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (d in DIVISOR_CANDIDATES) {
            if (captureRate % d == 0 && captureRate / d >= 1000) out.add(d to captureRate / d)
        }
        if (out.isEmpty()) out.add(1 to captureRate)
        return out
    }

    /** Pixel budget driven row count for the waterfall. */
    fun waterfallMaxRows(bins: Int): Int = (WATERFALL_PIXEL_BUDGET / bins.coerceAtLeast(1)).coerceAtLeast(64)

    /** Effective rows per second given the requested history length and pixel budget. */
    fun waterfallRowsPerSecond(bins: Int): Float {
        val history = waterfallHistorySec
        val maxRows = waterfallMaxRows(bins)
        return minOf(waterfallFps.toFloat(), maxRows / history).coerceAtLeast(0.5f)
    }

    companion object {
        const val RATE_AUTO = 0

        /** Upper bound for theme indices; ScopeTheme still clamps to its own list length. */
        private const val THEME_INDEX_LIMIT = 15

        val CAPTURE_RATE_OPTIONS = intArrayOf(RATE_AUTO, 48000, 44100, 96000, 192000)
        val FFT_SIZE_OPTIONS = intArrayOf(256, 512, 1024, 2048, 4096, 8192)
        val DIVISOR_CANDIDATES = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16)
        const val WATERFALL_PIXEL_BUDGET = 4_000_000

        private const val KEY_TEST_SIGNAL = "test_signal"
        private const val KEY_TEST_SIGNAL_TYPE = "test_signal_type"
        private const val KEY_TEST_SIGNAL_LEVEL = "test_signal_level"
        private const val KEY_CAPTURE_RATE = "capture_rate"
        private const val KEY_ANALYSIS_DIV = "analysis_div"
        private const val KEY_SPECTRUM_CHANNEL = "spectrum_channel"
        private const val KEY_FFT_SIZE = "fft_size"
        private const val KEY_WINDOW = "window"
        private const val KEY_FFT_OVERLAP = "fft_overlap"
        private const val KEY_AVG = "avg"
        private const val KEY_TILT = "tilt"
        private const val KEY_TILT_DB_OCT = "tilt_db_oct"
        private const val KEY_DB_FLOOR = "db_floor"
        private const val KEY_DB_TOP = "db_top"
        private const val KEY_PEAK_HOLD = "peak_hold"
        private const val KEY_PEAK_DECAY = "peak_decay"
        private const val KEY_FREQ_BLEND = "freq_blend"
        private const val KEY_BACKDROP = "scope_backdrop"
        private const val KEY_GLOW = "trace_glow"
        private const val KEY_ACCENT = "accent_theme"
        private const val KEY_SCOPE_BG = "scope_bg"
        private const val KEY_METER_LABELS = "meter_labels"
        private const val KEY_METER_LAYOUT = "meter_layout"
        private const val KEY_METER_SLIM = "meter_slim"
        private const val KEY_METER_SCALE = "meter_show_scale"
        private const val KEY_SHOW_READOUT = "show_readout"
        private const val KEY_CURSOR_NOTE = "cursor_note"
        private const val KEY_TUNING = "tuning_hz"
        private const val KEY_WF_HISTORY = "wf_history"
        private const val KEY_WF_COLORMAP = "wf_colormap"
        private const val KEY_WF_NEWEST_TOP = "wf_newest_top"
        private const val KEY_WF_FPS = "wf_fps"
        private const val KEY_WF_FREEZE = "wf_freeze"
        private const val KEY_WAVE_MODE = "wave_mode"
        private const val KEY_WAVE_MS_DIV = "wave_ms_div"
        private const val KEY_WAVE_GAIN_L = "wave_gain_l"
        private const val KEY_WAVE_GAIN_R = "wave_gain_r"
        private const val KEY_WAVE_LINK_GAIN = "wave_link_gain"
        private const val KEY_WAVE_TRIG_MODE = "wave_trig_mode"
        private const val KEY_WAVE_TRIG_LEVEL = "wave_trig_level"
        private const val KEY_WAVE_TRIG_EDGE = "wave_trig_edge"
        private const val KEY_WAVE_TRIG_SOURCE = "wave_trig_source"
        private const val KEY_WAVE_SOURCE = "wave_source"
        private const val KEY_WAVE_AC = "wave_ac"
        private const val KEY_WAVE_MEASURE = "wave_measure"
        private const val KEY_WAVE_DOTS = "wave_dots"
        private const val KEY_VEC_MODE = "vec_mode"
        private const val KEY_VEC_PERSIST = "vec_persist"
        private const val KEY_VEC_GAIN = "vec_gain"
        private const val KEY_VEC_AXES = "vec_axes"
        private const val KEY_VEC_TRACE_MS = "vec_trace_ms"
        private const val KEY_METER_HOLD_MS = "meter_hold_ms"
        private const val KEY_METER_CORR = "meter_corr"
        private const val KEY_METER_NUMERIC = "meter_numeric"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_WIN_CHROME = "win_chrome"
        private const val KEY_WIN_SNAP = "win_snap"
        private const val KEY_WIN_SNAP_DP = "win_snap_dp"
        private const val KEY_WIN_MATCH_SIZE = "win_match_size"
        private const val KEY_LINK_ZOOM = "link_zoom"
        private const val KEY_FPS = "render_fps"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_PREVIEW = "preview_in_app"
        private const val KEY_TOOL_PREFIX = "tool_"
        private const val KEY_LOCK_PREFIX = "lock_"
        private const val KEY_GEOM_PREFIX = "geom_"

        /** Every key, used by "reset everything". */
        private val KEYS = listOf(
            KEY_TEST_SIGNAL, KEY_TEST_SIGNAL_TYPE, KEY_TEST_SIGNAL_LEVEL,
            KEY_CAPTURE_RATE, KEY_ANALYSIS_DIV, KEY_SPECTRUM_CHANNEL, KEY_FFT_SIZE, KEY_WINDOW,
            KEY_FFT_OVERLAP, KEY_AVG, KEY_TILT, KEY_TILT_DB_OCT, KEY_DB_FLOOR, KEY_PEAK_HOLD,
            KEY_PEAK_DECAY, KEY_DB_TOP, KEY_FREQ_BLEND, KEY_SHOW_READOUT, KEY_CURSOR_NOTE, KEY_TUNING, KEY_WF_HISTORY, KEY_WF_COLORMAP,
            KEY_WF_NEWEST_TOP, KEY_WF_FPS, KEY_WF_FREEZE, KEY_WAVE_MODE, KEY_WAVE_MS_DIV,
            KEY_WAVE_GAIN_L, KEY_WAVE_GAIN_R, KEY_WAVE_LINK_GAIN, KEY_WAVE_TRIG_MODE, KEY_WAVE_TRIG_LEVEL,
            KEY_WAVE_TRIG_EDGE, KEY_WAVE_TRIG_SOURCE, KEY_WAVE_SOURCE, KEY_WAVE_AC,
            KEY_WAVE_MEASURE, KEY_WAVE_DOTS, KEY_VEC_MODE, KEY_VEC_PERSIST, KEY_VEC_GAIN,
            KEY_VEC_AXES, KEY_VEC_TRACE_MS, KEY_METER_HOLD_MS, KEY_METER_CORR, KEY_METER_NUMERIC,
            KEY_OVERLAY_OPACITY, KEY_WIN_CHROME, KEY_WIN_SNAP, KEY_WIN_SNAP_DP, KEY_WIN_MATCH_SIZE,
            KEY_LINK_ZOOM, KEY_FPS, KEY_KEEP_SCREEN_ON, KEY_PREVIEW,
            KEY_ACCENT, KEY_SCOPE_BG, KEY_METER_SLIM, KEY_METER_SCALE, KEY_METER_LAYOUT, KEY_METER_LABELS, KEY_BACKDROP, KEY_GLOW
        )

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }
    }
}

/** Persisted position and size (dp) of one floating tool window. */
data class WindowGeometry(val x: Int, val y: Int, val w: Int, val h: Int)

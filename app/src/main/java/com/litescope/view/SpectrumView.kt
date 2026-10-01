package com.litescope.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import com.litescope.core.Cursor
import com.litescope.dsp.Notes
import kotlin.math.abs

/**
 * Real-time spectrum analyser with a blended linear/log frequency axis, peak hold, optional pink
 * tilt, pinch zoom, and draggable measurement cursors.
 *
 * Gestures: pinch = zoom, drag = pan, double tap = reset zoom (peak trace and cursors are kept),
 * tap = add/remove a cursor, long press = lock a cursor / clear the peak trace.
 */
@SuppressLint("ClickableViewAccessibility") // gesture surface: touches drive zoom/pan/cursors
class SpectrumView(context: Context) : FreqScopeView(context, TOOL_ID) {

    private var peakDb = FloatArray(0)
    private var lastPeakDraw = 0L
    private val path = Path()
    private val fillPath = Path()
    private val holdPath = Path()
    private var fillShader: LinearGradient? = null
    private var shaderKey = ""

    private var draggingCursor: Cursor? = null
    private var dragAllowed = true
    private var cursorMoved = false
    private var cursorLongPressed = false

    /** Long press detection for cursor touches (they are consumed before the gesture detector). */
    private val cursorLongPress = Runnable {
        val cursor = draggingCursor ?: return@Runnable
        if (!cursorMoved) {
            cursorLongPressed = true
            cursors.toggleLock(cursor)
            invalidate()
        }
    }

    override fun drawScope(canvas: Canvas) {
        drawBackdrop(canvas)
        val p = palette
        val showLabels = width > dp(150f) && height > dp(72f)
        val left = if (showLabels) dp(ScopeMetrics.GUTTER_LEFT_DP) else dp(ScopeMetrics.PAD_DP)
        val top = dp(ScopeMetrics.PAD_DP)
        val right = width - dp(ScopeMetrics.PAD_DP)
        val bottom = height - (if (showLabels) dp(ScopeMetrics.GUTTER_BOTTOM_DP) else dp(ScopeMetrics.PAD_DP))
        if (right - left < 8f || bottom - top < 8f) return
        setPlot(left, right - left)

        applyGridColors()
        drawFrequencyGrid(canvas, left, top, right, bottom, bottom + dp(10f))
        drawDbGrid(canvas, left, top, right, bottom, showLabels)

        val frame = hub.spectrum.get()
        if (frame == null || frame.bins == 0) {
            drawHint(canvas, left, top, "waiting for playback audio…")
            drawCursors(canvas, top, bottom, handleAtBottom = false, drawLabels = false)
            return
        }
        val floorDb = prefs.dbFloor.toFloat()
        val topDb = prefs.dbTop.toFloat().coerceAtLeast(floorDb + 10f)
        val span = (topDb - floorDb).coerceAtLeast(1f)
        fun dbToY(db: Float): Float = top + (1f - (db - floorDb) / span) * (bottom - top)

        if (peakDb.size != frame.bins) peakDb = FloatArray(frame.bins)

        // ---- peak hold -------------------------------------------------------
        val now = SystemClock.uptimeMillis()
        val dt = if (lastPeakDraw == 0L) 0f else (now - lastPeakDraw) / 1000f
        lastPeakDraw = now
        if (prefs.peakHold && dt > 0f) {
            val decay = prefs.peakDecayDbPerSec * dt
            for (i in 0 until frame.bins) {
                val held = peakDb[i] - decay
                peakDb[i] = if (frame.db[i] > held) frame.db[i] else held
            }
        } else if (!prefs.peakHold && dt > 0f) {
            java.util.Arrays.fill(peakDb, floorDb)
        }

        val a = axis()
        val nyquist = frame.binHz * frame.bins

        // ---- spectrum curve --------------------------------------------------
        path.reset()
        fillPath.reset()
        var started = false
        var x = left
        while (x <= right) {
            val hz = a.xToHz(x - left, right - left)
            if (hz <= nyquist) {
                val db = interpolate(frame, hz)
                val y = dbToY(db)
                if (!started) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, bottom)
                    fillPath.lineTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            x += 1f
        }
        if (started) {
            fillPath.lineTo(right, bottom)
            fillPath.close()
            traceFillPaint.shader = gradient(top, bottom)
            canvas.drawPath(fillPath, traceFillPaint)
            traceFillPaint.shader = null
            tracePaint.color = p.accent
            drawTraced(canvas, path, tracePaint)
        }

        // ---- peak hold trace -------------------------------------------------
        if (prefs.peakHold) {
            holdPath.reset()
            var startedHold = false
            x = left
            while (x <= right) {
                val hz = a.xToHz(x - left, right - left)
                if (hz <= nyquist) {
                    val i = (hz / frame.binHz).toInt().coerceIn(0, frame.bins - 1)
                    val y = dbToY(peakDb[i])
                    if (!startedHold) {
                        holdPath.moveTo(x, y)
                        startedHold = true
                    } else {
                        holdPath.lineTo(x, y)
                    }
                }
                x += 1f
            }
            secondaryPaint.color = p.alpha(p.hold, 0.85f)
            if (startedHold) canvas.drawPath(holdPath, secondaryPaint)
        }

        drawCursors(canvas, top, bottom, handleAtBottom = false, drawLabels = true) { cursor ->
            val level = String.format(java.util.Locale.US, "%s  %.1f dB", formatHz(cursor.hz), interpolate(frame, cursor.hz))
            if (prefs.cursorNoteEnabled) {
                val note = Notes.describe(cursor.hz, prefs.tuningHz.toFloat())
                if (note.isNotEmpty()) "$level  $note" else level
            } else {
                level
            }
        }
        drawReadout(canvas, frame, left, top, right, bottom, showLabels)
    }

    private fun interpolate(frame: com.litescope.dsp.SpectrumFrame, hz: Float): Float {
        val pos = hz / frame.binHz
        val i0 = pos.toInt()
        val frac = pos - i0
        val v0 = frame.db[i0.coerceIn(0, frame.bins - 1)]
        val v1 = frame.db[(i0 + 1).coerceIn(0, frame.bins - 1)]
        return v0 + (v1 - v0) * frac.coerceIn(0f, 1f)
    }

    private fun drawDbGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        showLabels: Boolean
    ) {
        applyGridColors()
        val floor = prefs.dbFloor
        val ceil = prefs.dbTop.coerceAtLeast(floor + 10)
        val step = if (ceil - floor > 120) 20 else 10
        var db = ceil
        while (db >= floor) {
            val y = top + (1f - (db.toFloat() - floor) / (ceil - floor)) * (bottom - top)
            canvas.drawLine(left, y, right, y, if (db == ceil) gridMajorPaint else gridPaint)
            if (showLabels) {
                label(canvas, "$db", left - dp(3f), y + dp(3f), palette.dim, Paint.Align.RIGHT)
            }
            db -= step
        }
    }

    private fun drawHint(canvas: Canvas, left: Float, top: Float, text: String) {
        label(canvas, text, left + dp(6f), top + dp(16f), palette.dim)
    }

    private fun drawReadout(
        canvas: Canvas,
        frame: com.litescope.dsp.SpectrumFrame,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        showLabels: Boolean
    ) {
        if (!prefs.showReadout) return
        val p = palette
        val note = Notes.describe(frame.peakHz, prefs.tuningHz.toFloat())
        val chLabel = when (prefs.spectrumChannel) {
            1 -> "L"
            2 -> "R"
            else -> "L+R"
        }
        val text = String.format(
            java.util.Locale.US,
            "%s  %.1f dB  %s  [%s]",
            formatHz(frame.peakHz),
            frame.peakDb,
            note,
            chLabel
        )
        val w = labelWidth(text) + dp(10f)
        drawPanel(canvas, left + dp(3f), top + dp(3f), left + dp(3f) + w, top + dp(17f))
        label(canvas, text, left + dp(7f), top + dp(14f), p.text)

        val visible = cursors.visible()
        if (visible.size >= 2) {
            val (c0, c1) = deltaPair(visible)
            val dHz = abs(c1.hz - c0.hz)
            val dDb = abs(interpolate(frame, c1.hz) - interpolate(frame, c0.hz))
            val delta = String.format(
                java.util.Locale.US,
                "Δ %s  %.1f dB",
                formatHz(dHz),
                dDb
            )
            val dw = labelWidth(delta) + dp(10f)
            drawPanel(canvas, right - dw - dp(3f), top + dp(3f), right - dp(3f), top + dp(17f))
            label(canvas, delta, right - dw + dp(2f), top + dp(14f), p.accent)
        }
        if (showLabels) drawRangeText(canvas, right, bottom)
    }

    /**
     * The two cursors the delta readout refers to: the one the user just touched and its closest
     * neighbour, or simply the two lowest ones when nothing was touched yet. This keeps the readout
     * meaningful with any number of cursors on screen.
     */
    private fun deltaPair(visible: List<Cursor>): Pair<Cursor, Cursor> {
        val active = cursors.active
        if (active != null && visible.any { it === active }) {
            val other = visible.filter { it !== active }.minByOrNull { abs(it.hz - active.hz) }
            if (other != null) {
                return if (other.hz <= active.hz) other to active else active to other
            }
        }
        val sorted = visible.sortedBy { it.hz }
        return sorted[0] to sorted[1]
    }

    /** Shows the visible band in the bottom right, but never on top of the tick labels. */
    private fun drawRangeText(canvas: Canvas, right: Float, bottom: Float) {
        val a = axis()
        val text = "${formatHz(a.startHz)} – ${formatHz(a.endHz)}"
        val w = labelWidth(text)
        val x = right - dp(4f) - w
        if (x < lastTickLabelRight + dp(8f)) return
        label(canvas, text, right - dp(4f), bottom - dp(2f), palette.dim, Paint.Align.RIGHT)
    }

    private fun gradient(top: Float, bottom: Float): LinearGradient {
        val p = palette
        val key = "${p.accent}-${top.toInt()}-${bottom.toInt()}"
        val cached = fillShader
        if (cached != null && shaderKey == key) return cached
        val shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(p.alpha(p.accent, 0.42f), p.alpha(p.accent, 0.10f), p.alpha(p.accent, 0f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fillShader = shader
        shaderKey = key
        return shader
    }

    // ------------------------------------------------------------------ cursors

    /** Hz tolerance that corresponds to roughly 22 dp on screen. */
    private fun cursorToleranceHz(): Float {
        val a = axis()
        return (dp(22f) / plotWidth) * (a.endHz - a.startHz)
    }

    override fun onCursorTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = cursors.nearest(xToHz(event.x), cursorToleranceHz())
                if (hit != null) {
                    draggingCursor = hit
                    dragAllowed = !hit.locked
                    cursorMoved = false
                    cursorLongPressed = false
                    cursors.markActive(hit)
                    removeCallbacks(cursorLongPress)
                    postDelayed(cursorLongPress, LONG_PRESS_MS)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val cursor = draggingCursor ?: return false
                if (!dragAllowed) {
                    // A locked cursor is pinned: consume the gesture but never move it.
                    return true
                }
                val hz = xToHz(event.x).coerceAtLeast(0f)
                if (abs(hz - cursor.hz) * plotWidth / (axis().endHz - axis().startHz).coerceAtLeast(1f) >
                    dp(3f)
                ) {
                    cursorMoved = true
                    removeCallbacks(cursorLongPress)
                }
                cursor.hz = hz
                cursors.touch()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(cursorLongPress)
                val cursor = draggingCursor
                draggingCursor = null
                if (cursor == null) return false
                if (!cursorMoved && !cursorLongPressed && !cursor.locked) {
                    // A tap right on an unlocked marker removes it; locked ones need unlocking first.
                    cursors.remove(cursor)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(cursorLongPress)
                draggingCursor = null
                return false
            }
        }
        return false
    }

    override fun onScopeTap(x: Float, y: Float) {
        // Taps on a cursor are consumed above; reaching this point means "add a cursor here".
        if (x < plotLeft || x > plotLeft + plotWidth) return
        cursors.add(xToHz(x))
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    override fun onScopeLongPress(x: Float, y: Float) {
        val hit = cursors.nearest(xToHz(x), cursorToleranceHz())
        if (hit != null) {
            cursors.toggleLock(hit)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } else {
            // Long press on empty space still clears the peak-hold trace.
            java.util.Arrays.fill(peakDb, prefs.dbFloor.toFloat())
        }
        invalidate()
    }

    override fun resetView() {
        super.resetView()
        peakDb = FloatArray(0)
        cursors.touch()
        invalidate()
    }

    companion object {
        const val TOOL_ID = "spectrum"
        private const val LONG_PRESS_MS = 500L
    }
}

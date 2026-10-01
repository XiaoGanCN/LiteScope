package com.litescope.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.litescope.core.Cursor
import com.litescope.core.CursorStore
import com.litescope.core.FreqAxis
import com.litescope.core.Prefs
import com.litescope.core.ScopeHub
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Layout metrics shared by every frequency-domain scope. The spectrum and the waterfall use the
 * same gutters, so when the two windows are snapped together their plots - and every grid line -
 * line up exactly.
 */
object ScopeMetrics {
    /** Width of the left gutter that carries the value scale (dB for the spectrum, time here). */
    const val GUTTER_LEFT_DP = 34f

    /** Height of the bottom gutter that carries the frequency labels. */
    const val GUTTER_BOTTOM_DP = 15f

    /** Padding between the plot and the window edge. */
    const val PAD_DP = 3f
}

/**
 * Common scaffolding for every scope: shared paints, a frame-rate capped redraw loop that stops when
 * the view is detached, and a last-resort guard so a drawing bug can never take down the overlay
 * service process.
 */
abstract class ScopeView(context: Context, val toolId: String) : View(context), Prefs.Listener {

    protected val hub: ScopeHub = ScopeHub.get(context)
    protected val prefs: Prefs = hub.prefs
    protected val cursors: CursorStore = hub.cursors
    protected val density: Float = resources.displayMetrics.density

    protected val palette: Palette get() = ScopeTheme.palette(prefs)

    protected val bgPaint = Paint()
    protected val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val gridMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val traceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backdropShader: Shader? = null
    private var backdropKey = ""
    private var dotPoints = FloatArray(0)
    private var dotKey = ""

    private var attached = false
    private var lastDrawAt = 0L
    private var errorCount = 0
    private var lastError: String? = null
    private val frameRunnable = Runnable { if (attached) invalidate() }

    init {
        setWillNotDraw(false)
        bgPaint.style = Paint.Style.FILL
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = dp(1f)
        gridMajorPaint.style = Paint.Style.STROKE
        gridMajorPaint.strokeWidth = dp(1f)
        labelPaint.textSize = 9f * density
        labelPaint.typeface = Typeface.MONOSPACE
        tracePaint.style = Paint.Style.STROKE
        tracePaint.strokeWidth = dp(1.5f)
        tracePaint.strokeJoin = Paint.Join.ROUND
        traceFillPaint.style = Paint.Style.FILL
        secondaryPaint.style = Paint.Style.STROKE
        secondaryPaint.strokeWidth = dp(1.1f)
        cursorPaint.style = Paint.Style.STROKE
        cursorPaint.strokeWidth = dp(1.1f)
        panelPaint.style = Paint.Style.FILL
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeJoin = Paint.Join.ROUND
        backdropPaint.style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!attached) {
            attached = true
            prefs.addListener(this)
            onScopeAttached()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        if (attached) {
            attached = false
            prefs.removeListener(this)
            removeCallbacks(frameRunnable)
            onScopeDetached()
        }
        super.onDetachedFromWindow()
    }

    override fun onPrefsChanged(keys: Set<String>) {
        onScopePrefsChanged(keys)
        invalidate()
    }

    protected open fun onScopeAttached() {}
    protected open fun onScopeDetached() {}
    protected open fun onScopePrefsChanged(keys: Set<String>) {}

    /** Last rendering failure (null while drawing is healthy). Exposed for tests. */
    val lastRenderError: String? get() = lastError

    /** Reverts pan/zoom/gain/cursors to their defaults. Bound to double tap and the "reset" button. */
    open fun resetView() {
        invalidate()
    }

    final override fun onDraw(canvas: Canvas) {
        try {
            drawScope(canvas)
            lastError = null
        } catch (t: Throwable) {
            // A scope must never crash the capture service: report once and keep the window alive.
            errorCount++
            val message = t::class.java.simpleName + ": " + t.message
            if (message != lastError) {
                lastError = message
                Log.e(TAG, "render error in $toolId (#$errorCount)", t)
            }
            drawRenderError(canvas, message)
        }
        scheduleNextFrame()
    }

    private fun drawRenderError(canvas: Canvas, message: String) {
        val p = palette
        bgPaint.color = p.bg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        labelPaint.color = p.bad
        canvas.drawText("render error", dp(8f), dp(18f), labelPaint)
        labelPaint.color = p.dim
        canvas.drawText(message.take(48), dp(8f), dp(32f), labelPaint)
    }

    protected abstract fun drawScope(canvas: Canvas)

    private fun scheduleNextFrame() {
        if (!attached) return
        val fps = prefs.renderFps.coerceIn(5, 60)
        val interval = 1000L / fps
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastDrawAt
        lastDrawAt = now
        val delay = (interval - elapsed).coerceAtLeast(0L)
        if (delay <= 2L) {
            postInvalidateOnAnimation()
        } else {
            removeCallbacks(frameRunnable)
            postDelayed(frameRunnable, delay)
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    protected fun dp(value: Float): Float = value * density

    protected fun dpInt(value: Int): Int = (value * density).toInt()

    protected fun drawBackdrop(canvas: Canvas) {
        val p = palette
        // Window opacity fades the backdrop only: traces, cursors and readouts stay crisp.
        bgPaint.color = p.alpha(p.bg, prefs.overlayOpacity / 100f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        when (prefs.scopeBackdrop) {
            1 -> drawDotMatrix(canvas, p)
            2 -> drawVignette(canvas, p)
            3 -> drawScanlines(canvas, p)
        }
    }

    /** Nothing-inspired dot matrix backdrop. */
    private fun drawDotMatrix(canvas: Canvas, p: Palette) {
        val step = dp(11f)
        val key = "${width}x${height}"
        if (dotKey != key) {
            val cols = (width / step).toInt() + 1
            val rows = (height / step).toInt() + 1
            val points = FloatArray(cols * rows * 2)
            var i = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    points[i++] = c * step + step / 2f
                    points[i++] = r * step + step / 2f
                }
            }
            dotPoints = points
            dotKey = key
        }
        if (dotPoints.isEmpty()) return
        glowPaint.style = Paint.Style.FILL
        glowPaint.color = p.alpha(p.text, if (p.isLight) 0.10f else 0.07f)
        glowPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.strokeWidth = dp(1.6f)
        canvas.drawPoints(dotPoints, glowPaint)
        glowPaint.style = Paint.Style.STROKE
    }

    /** Soft vignette that fades the corners into the background. */
    private fun drawVignette(canvas: Canvas, p: Palette) {
        val key = "${width}x${height}-${p.bg}"
        if (backdropKey != key || backdropShader == null) {
            val radius = maxOf(width, height) * 0.75f
            backdropShader = RadialGradient(
                width / 2f, height / 2f, radius,
                intArrayOf(0x00000000, 0x00000000, p.alpha(0xFF000000.toInt(), if (p.isLight) 0.10f else 0.55f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            backdropKey = key
        }
        backdropPaint.shader = backdropShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
        backdropPaint.shader = null
    }

    /** Subtle CRT-style scanlines. */
    private fun drawScanlines(canvas: Canvas, p: Palette) {
        backdropPaint.color = p.alpha(0xFFFFFFFF.toInt(), if (p.isLight) 0.05f else 0.035f)
        val step = dp(3f)
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + dp(1f), backdropPaint)
            y += step
        }
    }

    /**
     * Draws a path with an optional soft glow underneath, which is what gives the traces their
     * "lit phosphor" look on dark themes.
     */
    protected fun drawTraced(canvas: Canvas, path: Path, paint: Paint) {
        if (prefs.traceGlow) {
            glowPaint.color = palette.alpha(paint.color, 0.22f)
            glowPaint.strokeWidth = paint.strokeWidth * 3.4f
            canvas.drawPath(path, glowPaint)
        }
        canvas.drawPath(path, paint)
    }

    protected fun label(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        labelPaint.color = color
        labelPaint.textAlign = align
        canvas.drawText(text, x, y, labelPaint)
    }

    protected fun labelWidth(text: String): Float = labelPaint.measureText(text)

    protected fun applyGridColors() {
        val p = palette
        gridPaint.color = p.grid
        gridMajorPaint.color = p.gridMajor
    }

    /** Rounded translucent panel used by the readouts, with a soft accent glow. */
    protected fun drawPanel(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val p = palette
        val radius = dp(6f)
        glowPaint.style = Paint.Style.STROKE
        glowPaint.color = p.alpha(p.accent, 0.18f)
        glowPaint.strokeWidth = dp(3.5f)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, glowPaint)
        glowPaint.color = p.alpha(p.accent, 0.35f)
        glowPaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, glowPaint)
        panelPaint.color = p.panel
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, panelPaint)
    }

    companion object {
        private const val TAG = "LiteScope/View"
    }
}

/**
 * Base class for the frequency-domain tools. Owns the frequency axis (shared or local), the
 * pinch/pan gestures, the frequency grid and the measurement cursor rendering, so the spectrum and
 * the waterfall always look and behave the same.
 */
abstract class FreqScopeView(context: Context, toolId: String) : ScopeView(context, toolId) {

    /** Used when [Prefs.linkZoom] is off: this view then zooms independently. */
    private val localAxis = FreqAxis()

    /** True when this view currently drives/draws the shared axis. */
    protected val linked: Boolean get() = prefs.linkZoom

    protected fun axis(): FreqAxis {
        val a = if (linked) hub.freqAxis else localAxis
        a.configure(hub.nyquistHz, prefs.freqScaleBlend)
        return a
    }

    private var lastTouchX = 0f
    private var dragging = false
    private var axisTouched = false

    /** Raw distance between two fingers, tracked manually so pinching works at any finger spread. */
    private var pointerSpan = 0f

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetAxis()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onScopeLongPress(e.x, e.y)
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onScopeTap(e.x, e.y)
                return true
            }
        }
    )

    /** Right edge of the last frequency label drawn, used to avoid overlapping the range text. */
    protected var lastTickLabelRight = 0f

    /** Plot rectangle, updated every frame so gestures can map touches to frequencies. */
    protected var plotLeft = 0f
        private set
    protected var plotWidth = 1f
        private set

    protected fun setPlot(left: Float, width: Float) {
        plotLeft = left
        plotWidth = width.coerceAtLeast(1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A cursor drag owns the entire gesture: previously the pan below still ran alongside it,
        // which made grabbing a marker impossible once the axis was zoomed in.
        val cursorOwnsGesture = onCursorTouchEvent(event)
        if (cursorOwnsGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                dragging = false
                pointerSpan = 0f
                if (axisTouched) {
                    axisTouched = false
                    onAxisInteractionFinished()
                }
            }
            return true
        }
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                pointerSpan = 0f
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerSpan = spanOf(event)
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    // Pinch: zoom around the midpoint of the two fingers. Comparing the raw span
                    // each frame reacts to any finger spread, unlike the scale detector.
                    val span = spanOf(event)
                    val focus = (event.getX(0) + event.getX(1)) / 2f
                    if (pointerSpan > dp(8f) && span > dp(8f)) {
                        val a = axis()
                        a.zoomAt(a.xToHz(focus - plotLeft, plotWidth), pointerSpan / span)
                        axisTouched = true
                        onAxisChanged()
                        invalidate()
                    }
                    pointerSpan = span
                    dragging = false
                } else if (dragging) {
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x
                    if (abs(dx) > 0.5f) {
                        val a = axis()
                        a.panByFraction(-dx / plotWidth)
                        axisTouched = true
                        onAxisChanged()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> pointerSpan = 0f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pointerSpan = 0f
                if (axisTouched) {
                    axisTouched = false
                    onAxisInteractionFinished()
                }
            }
        }
        return true
    }

    private fun spanOf(event: MotionEvent): Float =
        if (event.pointerCount >= 2) abs(event.getX(0) - event.getX(1)) else 0f

    /** Overridden by the spectrum to give cursors priority over the pan gesture. */
    protected open fun onCursorTouchEvent(event: MotionEvent): Boolean = false

    /** Resets only the frequency window (never the peak trace or cursors). */
    protected open fun resetAxis() {
        axis().reset()
        onAxisChanged()
        invalidate()
    }

    /** Called after any zoom/pan so subclasses can react. */
    protected open fun onAxisChanged() {}

    /** Called once when a pinch/drag gesture ends. */
    protected open fun onAxisInteractionFinished() {}

    protected open fun onScopeLongPress(x: Float, y: Float) {}

    protected open fun onScopeTap(x: Float, y: Float) {}

    protected fun xToHz(x: Float): Float = axis().xToHz(x - plotLeft, plotWidth)

    protected fun hzToX(hz: Float): Float = plotLeft + axis().hzToX(hz, plotWidth)

    // ------------------------------------------------------------------ frequency axis rendering

    /** Draws the vertical frequency grid lines into the plot area and their labels under it. */
    protected fun drawFrequencyGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelY: Float
    ) {
        applyGridColors()
        val a = axis()
        val ticks = frequencyTicks()
        var lastLabelRight = -1e9f
        lastTickLabelRight = 0f
        for (hz in ticks) {
            val x = left + a.hzToX(hz, right - left)
            if (x < left - 0.5f || x > right + 0.5f) continue
            val major = isMajorTick(hz)
            canvas.drawLine(x, top, x, bottom, if (major) gridMajorPaint else gridPaint)
            if (!major) continue
            val text = formatHz(hz)
            val w = labelWidth(text)
            // Keep the label centred on its tick: clamping it (as before) made dense log ranges
            // look like the labels had drifted away from the grid lines.
            val textX = x - w / 2f
            if (textX < left - dp(1f) || textX + w > right + dp(1f)) continue
            // Skip labels that would collide with the previous one.
            if (textX < lastLabelRight + dp(6f)) continue
            label(canvas, text, textX, labelY, palette.dim)
            lastLabelRight = textX + w
            lastTickLabelRight = lastLabelRight
        }
    }

    /** Frequencies of the grid lines for the current span and scale. */
    protected fun frequencyTicks(): FloatArray {
        val a = axis()
        val out = ArrayList<Float>(24)
        val blend = prefs.freqScaleBlend
        if (blend > 0.5f && a.startHz > 0f) {
            var decade = 1f
            while (decade <= a.endHz * 2f && decade < 1e6f) {
                for (m in intArrayOf(1, 2, 5)) {
                    val hz = decade * m
                    if (hz in a.startHz..a.endHz) out.add(hz)
                }
                decade *= 10f
            }
        } else {
            val span = a.spanHz
            if (span > 0f) {
                val rawStep = span / 8f
                val magnitude = 10f.pow(kotlin.math.floor(log10(rawStep.toDouble())).toFloat())
                val step = when {
                    rawStep / magnitude <= 1.5f -> magnitude
                    rawStep / magnitude <= 3.5f -> 2f * magnitude
                    rawStep / magnitude <= 7.5f -> 5f * magnitude
                    else -> 10f * magnitude
                }
                var hz = kotlin.math.ceil(a.startHz / step) * step
                while (hz <= a.endHz && out.size < 40) {
                    out.add(hz)
                    hz += step
                }
            }
        }
        return out.toFloatArray()
    }

    private fun isMajorTick(hz: Float): Boolean {
        if (hz <= 0f) return false
        val r = hz / 10f.pow(kotlin.math.floor(log10(hz.toDouble())).toFloat())
        return abs(r - 1f) < 0.01f || abs(r - 2f) < 0.01f || abs(r - 5f) < 0.01f
    }

    protected fun formatHz(hz: Float): String = when {
        hz >= 1_000_000f -> String.format(java.util.Locale.US, "%.1fM", hz / 1_000_000f)
        hz >= 10_000f -> String.format(java.util.Locale.US, "%.0fk", hz / 1000f)
        hz >= 1000f -> String.format(java.util.Locale.US, "%.1fk", hz / 1000f)
        hz >= 10f -> String.format(java.util.Locale.US, "%.0f", hz)
        else -> String.format(java.util.Locale.US, "%.1f", hz)
    }

    // ------------------------------------------------------------------ cursors

    /** Draws every visible measurement cursor. [handleAtBottom] flips the grab handle. */
    protected fun drawCursors(
        canvas: Canvas,
        top: Float,
        bottom: Float,
        handleAtBottom: Boolean,
        drawLabels: Boolean,
        labelFor: (Cursor) -> String = { formatHz(it.hz) }
    ) {
        val visible = cursors.visible()
        if (visible.isEmpty()) return
        val p = palette
        val fading = cursors.fading()
        var labelRow = 0
        for (cursor in visible) {
            val x = hzToX(cursor.hz)
            if (x < plotLeft - 2f || x > plotLeft + plotWidth + 2f) continue
            cursorPaint.color = if (cursor.locked) p.accent else p.accentAlt
            cursorPaint.alpha = if (fading) 90 else 255
            canvas.drawLine(x, top, x, bottom, cursorPaint)
            val handleY = if (handleAtBottom) bottom - dp(7f) else top + dp(7f)
            cursorPaint.style = Paint.Style.FILL
            cursorPaint.color = p.bg
            canvas.drawCircle(x, handleY, dp(5.5f), cursorPaint)
            cursorPaint.style = Paint.Style.STROKE
            cursorPaint.color = if (cursor.locked) p.accent else p.accentAlt
            cursorPaint.alpha = if (fading) 90 else 255
            canvas.drawCircle(x, handleY, dp(5.5f), cursorPaint)
            if (cursor.locked) {
                canvas.drawCircle(x, handleY, dp(1.8f), cursorPaint)
            }
            if (drawLabels) {
                labelPaint.alpha = if (fading) 120 else 255
                val text = labelFor(cursor)
                val w = labelWidth(text)
                val textX = (x + dp(7f)).coerceAtMost(plotLeft + plotWidth - w)
                val y = if (handleAtBottom) bottom - dp(11f) - labelRow * dp(11f)
                else top + dp(32f) + labelRow * dp(11f)
                label(canvas, text, textX, y, if (cursor.locked) p.accent else p.text)
                labelPaint.alpha = 255
                labelRow++
            }
        }
        cursorPaint.alpha = 255
        cursorPaint.style = Paint.Style.STROKE
    }
}

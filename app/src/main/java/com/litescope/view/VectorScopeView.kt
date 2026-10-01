package com.litescope.view

import android.annotation.SuppressLint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lissajous / goniometer display of the stereo image, drawn onto a phosphor bitmap that fades a
 * little every frame so the persistence of the trace is visible.
 *
 * Gestures: pinch = vector gain, double tap = reset gain and clear the phosphor.
 */
@SuppressLint("ClickableViewAccessibility") // gesture surface: touches drive zoom/pan, not clicks
class VectorScopeView(context: Context) : ScopeView(context, TOOL_ID) {

    private var phosphor: Bitmap? = null
    private var phosphorCanvas: Canvas? = null
    private var phosphorW = 0
    private var phosphorH = 0
    private var samples = FloatArray(0)

    private val fadePaint = Paint()
    private val phosTracePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plot = RectF()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = if (detector.scaleFactor > 0f) detector.scaleFactor else 1f
                prefs.vectorGain = (prefs.vectorGain * f).coerceIn(0.05f, 8f)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun resetView() {
        prefs.vectorGain = 1f
        clearPhosphor()
        invalidate()
    }

    private fun clearPhosphor() {
        phosphor?.eraseColor(0)
    }

    private fun ensurePhosphor(w: Int, h: Int) {
        if (phosphor != null && phosphorW == w && phosphorH == h) return
        phosphor?.recycle()
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0)
        phosphor = bmp
        phosphorCanvas = Canvas(bmp)
        phosphorW = w
        phosphorH = h
    }

    override fun onScopeDetached() {
        phosphor?.recycle()
        phosphor = null
        phosphorCanvas = null
        phosphorW = 0
        phosphorH = 0
    }

    override fun drawScope(canvas: Canvas) {
        drawBackdrop(canvas)
        val p = palette
        val w = width
        val h = height
        if (w < 16 || h < 16) return
        if (lastMode != prefs.vectorMode) {
            lastMode = prefs.vectorMode
            clearPhosphor()
        }

        val showLabels = w > dp(120f) && h > dp(120f)
        val pad = if (showLabels) dp(14f) else dp(3f)
        val size = min(w, h) - pad * 2f
        val cx = w / 2f
        val cy = h / 2f
        plot.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)

        drawGrid(canvas, cx, cy, size / 2f, showLabels, p)

        val rate = hub.bus.sampleRate
        if (rate <= 0) {
            label(canvas, "no capture", plot.left + dp(4f), plot.top + dp(14f), p.dim)
            return
        }

        val traceFrames = ((prefs.vectorTraceMs / 1000f) * rate).toInt().coerceIn(32, 16384)
        if (samples.size < traceFrames * 2) samples = FloatArray(traceFrames * 2)
        val got = hub.bus.copyLatestStereo(samples, traceFrames)

        ensurePhosphor(w, h)
        val pc = phosphorCanvas
        val bmp = phosphor
        if (pc != null && bmp != null && got > 1) {
            // Fade the previous trace.
            val alpha = ((1f - prefs.vectorPersistence) * 110f).toInt().coerceIn(6, 110)
            fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            fadePaint.color = Color.argb(alpha, 0, 0, 0)
            fadePaint.style = Paint.Style.FILL
            pc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)
            fadePaint.xfermode = null

            val gain = prefs.vectorGain * (size / 2f) * 0.92f
            val goniometer = prefs.vectorMode == 1
            val inv = 1f / sqrt(2f)
            phosTracePaint.style = Paint.Style.STROKE
            phosTracePaint.strokeWidth = dp(1.3f)
            phosTracePaint.color = p.accent
            var px = 0f
            var py = 0f
            var started = false
            for (i in 0 until got) {
                val l = samples[i * 2]
                val r = samples[i * 2 + 1]
                val xv: Float
                val yv: Float
                if (goniometer) {
                    xv = (l - r) * inv
                    yv = (l + r) * inv
                } else {
                    xv = l
                    yv = r
                }
                val x = cx + xv * gain
                val y = cy - yv * gain
                if (!started) {
                    px = x
                    py = y
                    started = true
                } else {
                    pc.drawLine(px, py, x, y, phosTracePaint)
                    px = x
                    py = y
                }
            }
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }

        // Readout.
        val levels = hub.levels.get()
        if (levels != null && prefs.showReadout) {
            val corr = levels.correlation
            val balance = 20f * kotlin.math.log10(max(levels.rmsR, 1e-6f) / max(levels.rmsL, 1e-6f))
            val widthPct = if (levels.midRms + levels.sideRms > 1e-6f) {
                100f * levels.sideRms / (levels.midRms + levels.sideRms)
            } else {
                0f
            }
            label(
                canvas,
                String.format(java.util.Locale.US, "corr %+.2f", corr),
                plot.left + dp(4f),
                plot.top + dp(11f),
                if (corr < -0.2f) p.bad else p.text
            )
            if (showLabels) {
                label(
                    canvas,
                    String.format(java.util.Locale.US, "width %.0f%%", widthPct),
                    plot.left + dp(4f),
                    plot.top + dp(23f),
                    p.dim
                )
                label(
                    canvas,
                    String.format(java.util.Locale.US, "bal %+.1f dB", balance),
                    plot.right - dp(4f),
                    plot.bottom - dp(4f),
                    p.dim,
                    Paint.Align.RIGHT
                )
            }
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        showLabels: Boolean,
        p: Palette
    ) {
        applyGridColors()
        canvas.drawCircle(cx, cy, radius, gridPaint)
        canvas.drawCircle(cx, cy, radius * 0.5f, gridPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint)
        if (prefs.vectorShowAxes) {
            val d = radius * 0.7071f
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, gridPaint)
            canvas.drawLine(cx - d, cy + d, cx + d, cy - d, gridPaint)
            if (showLabels) {
                if (prefs.vectorMode == 1) {
                    label(canvas, "L", cx - d + dp(2f), cy - d + dp(10f), p.accent)
                    label(canvas, "R", cx + d - dp(10f), cy - d + dp(10f), p.accentAlt)
                    label(canvas, "M", cx + dp(3f), cy - radius + dp(10f), p.dim)
                    label(canvas, "S", cx + radius - dp(9f), cy - dp(3f), p.dim)
                } else {
                    label(canvas, "L", cx - radius + dp(2f), cy - dp(3f), p.accent)
                    label(canvas, "R", cx + radius - dp(9f), cy - dp(3f), p.accentAlt)
                    label(canvas, "L=R", cx + d - dp(16f), cy - d + dp(10f), p.dim)
                    label(canvas, "L=-R", cx - d + dp(2f), cy - d + dp(10f), p.dim)
                }
            }
        }
    }

    override fun onScopePrefsChanged(keys: Set<String>) {
        if (keys.contains("vec_persist")) clearPhosphor()
    }

    private var lastMode = -1

    companion object {
        const val TOOL_ID = "vector"
    }
}

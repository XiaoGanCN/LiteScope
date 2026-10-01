package com.litescope.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import com.litescope.dsp.Colormap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scrolling spectrogram.
 *
 * Shares the frequency axis with the spectrum scope when *linked*, which also mirrors the
 * measurement cursors, so the two tools stay column-aligned when snapped together. Time labels live
 * in the dedicated gutter on the left; frequency labels sit under the plot.
 *
 * Gestures: pinch = zoom frequency, drag = pan, tap = freeze/unfreeze, long press = clear history,
 * double tap = reset zoom.
 */
class WaterfallView(context: Context) : FreqScopeView(context, TOOL_ID) {

    // Nearest neighbour on purpose: filtering blends neighbouring rows together, which reads as a
    // smearing "jelly" edge at high row rates or with a short history.
    private val bitmapPaint = Paint()
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frozenAtFrame = -1L

    /** Reused strip buffer: four floats per strip (src left/right fraction, dst left/right). */
    private val stripData = FloatArray(MAX_STRIPS * 4)

    override fun drawScope(canvas: Canvas) {
        drawBackdrop(canvas)
        val p = palette
        val showLabels = width > dp(140f) && height > dp(70f)
        val left = if (showLabels) dp(ScopeMetrics.GUTTER_LEFT_DP) else dp(ScopeMetrics.PAD_DP)
        val top = dp(ScopeMetrics.PAD_DP)
        val right = width - dp(ScopeMetrics.PAD_DP)
        val bottom = height - (if (showLabels) dp(ScopeMetrics.GUTTER_BOTTOM_DP) else dp(ScopeMetrics.PAD_DP))
        if (right - left < 8f || bottom - top < 8f) return
        setPlot(left, right - left)

        val wf = hub.waterfall
        val frame = hub.spectrum.get()
        val nyquist = frame?.let { it.binHz * it.bins } ?: hub.nyquistHz
        val a = axis()

        if (wf.bins > 0 && wf.hasData && nyquist > 0f) {
            val f0 = a.startHz.coerceAtLeast(0f)
            val f1 = a.endHz.coerceAtMost(nyquist)
            if (f1 > f0) {
                // Use the achieved row rate: the analyser may deliver fewer frames than requested,
                // and assuming the requested rate made the history look longer than it is.
                val wanted = prefs.waterfallRowsPerSecond(wf.bins)
                val rowsPerSecond = hub.actualWaterfallRowsPerSecond
                    .takeIf { it > 0.5f }?.coerceAtMost(wanted) ?: wanted
                val visible = min(
                    wf.rows,
                    max(1, (prefs.waterfallHistorySec * rowsPerSecond).roundToInt())
                )
                val strips = buildStrips(left, right, nyquist)
                wf.render(
                    canvas = canvas,
                    top = top,
                    bottom = bottom,
                    visibleRows = visible,
                    newestOnTop = prefs.waterfallNewestOnTop,
                    paint = bitmapPaint,
                    strips = stripData,
                    stripCount = strips
                )
            }
        } else {
            label(canvas, "waterfall history empty", left + dp(6f), top + dp(16f), p.dim)
        }

        applyGridColors()
        drawFrequencyGrid(canvas, left, top, right, bottom, bottom + dp(10f))
        if (showLabels) drawTimeGutter(canvas, top, bottom, left)
        drawCursors(canvas, top, bottom, handleAtBottom = true, drawLabels = true) { cursor ->
            val level = frame?.let { f ->
                val i = (cursor.hz / f.binHz).toInt().coerceIn(0, f.bins - 1)
                f.db[i]
            }
            val base = if (level != null) {
                String.format(java.util.Locale.US, "%s  %.1f dB", formatHz(cursor.hz), level)
            } else {
                formatHz(cursor.hz)
            }
            if (prefs.cursorNoteEnabled) {
                val note = com.litescope.dsp.Notes.describe(cursor.hz, prefs.tuningHz.toFloat())
                if (note.isNotEmpty()) "$base  $note" else base
            } else {
                base
            }
        }
        if (prefs.showReadout) drawReadout(canvas, left, top, right, bottom)
    }

    /**
     * Splits the plot into narrow columns and computes the source fraction each column maps to.
     * On a linear axis a single strip is exact; on a logarithmic (or blended) axis each column gets
     * its own source range so the bitmap follows the labels instead of being stretched linearly.
     */
    private fun buildStrips(left: Float, right: Float, nyquist: Float): Int {
        val linear = prefs.freqScaleBlend < 0.05f
        val width = (right - left).coerceAtLeast(1f)
        val count = if (linear) {
            1
        } else {
            (width / dp(3f)).toInt().coerceIn(8, MAX_STRIPS)
        }
        val step = width / count
        for (i in 0 until count) {
            val x0 = left + i * step
            val x1 = if (i == count - 1) right else x0 + step
            val f0 = xToHz(x0).coerceIn(0f, nyquist)
            val f1 = xToHz(x1).coerceIn(0f, nyquist)
            val base = i * 4
            stripData[base] = f0 / nyquist
            stripData[base + 1] = f1 / nyquist
            stripData[base + 2] = x0
            stripData[base + 3] = x1
        }
        return count
    }

    /** Age labels down the dedicated left gutter, with ticks just inside the plot. */
    private fun drawTimeGutter(canvas: Canvas, top: Float, bottom: Float, plotLeft: Float) {
        val seconds = prefs.waterfallHistorySec
        if (seconds <= 0f) return
        val p = palette
        tickPaint.color = p.gridMajor
        tickPaint.strokeWidth = dp(1f)
        val steps = 4
        for (i in 0..steps) {
            if (i == 0) continue
            val fraction = i.toFloat() / steps
            val y = top + (if (prefs.waterfallNewestOnTop) fraction else 1f - fraction) * (bottom - top)
            canvas.drawLine(plotLeft, y, plotLeft + dp(4f), y, tickPaint)
            val text = String.format(java.util.Locale.US, "-%.1fs", seconds * fraction)
            label(canvas, text, plotLeft - dp(5f), y + dp(3f), p.dim, Paint.Align.RIGHT)
        }
    }

    private fun drawReadout(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val p = palette
        val wf = hub.waterfall
        val wanted = prefs.waterfallRowsPerSecond(wf.bins.coerceAtLeast(1))
        val rowsPerSecond = hub.actualWaterfallRowsPerSecond
            .takeIf { it > 0.5f }?.coerceAtMost(wanted) ?: wanted
        val map = Colormap.fromOrdinal(prefs.waterfallColormap).label
        val linkedTag = if (linked) " · linked" else ""
        val frozen = if (prefs.waterfallFreeze) " · HOLD" else ""
        val text = String.format(
            java.util.Locale.US,
            "%.1fs · %.0f rows/s · %s%s%s",
            prefs.waterfallHistorySec,
            rowsPerSecond,
            map,
            linkedTag,
            frozen
        )
        val w = labelWidth(text) + dp(10f)
        drawPanel(canvas, left + dp(3f), top + dp(3f), min(left + dp(3f) + w, right), top + dp(17f))
        label(
            canvas,
            text,
            left + dp(7f),
            top + dp(14f),
            if (prefs.waterfallFreeze) p.warn else p.text
        )
        val rangeText = "${formatHz(axis().startHz)} – ${formatHz(axis().endHz)}"
        val rangeWidth = labelWidth(rangeText)
        if (right - dp(4f) - rangeWidth >= lastTickLabelRight + dp(8f)) {
            label(canvas, rangeText, right - dp(4f), bottom - dp(2f), p.dim, Paint.Align.RIGHT)
        }
    }

    override fun onScopeLongPress(x: Float, y: Float) {
        hub.clearWaterfall()
        frozenAtFrame = -1L
        invalidate()
    }

    override fun onScopeTap(x: Float, y: Float) {
        prefs.waterfallFreeze = !prefs.waterfallFreeze
        frozenAtFrame = if (prefs.waterfallFreeze) SystemClock.elapsedRealtime() else -1L
        invalidate()
    }

    override fun resetAxis() {
        super.resetAxis()
        invalidate()
    }

    companion object {
        const val TOOL_ID = "waterfall"

        /** Maximum number of columns the history is drawn in when the axis is non-linear. */
        private const val MAX_STRIPS = 256
    }
}

package com.litescope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.litescope.view.Palette

/**
 * Two-handle range slider used for the spectrum's dB window.
 *
 * Both handles are always visible, the selected span is highlighted in the accent colour and the
 * current values are printed above the thumbs, so the control explains itself without a legend.
 */
class RangeSlider(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var minValue: Float = -180f
    var maxValue: Float = 0f
    var step: Float = 1f
    var lowValue: Float = -100f
        private set
    var highValue: Float = 0f
        private set

    /** Called whenever either handle moves. */
    var onRangeChanged: ((Float, Float) -> Unit)? = null

    /** Double tapping the control restores this window. */
    var defaultLow: Float = -100f
    var defaultHigh: Float = 0f

    private var palette: Palette? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val thumb = RectF()

    private var dragging: Int = 0 // 0 none, 1 low, 2 high

    /**
     * Set when a double tap resets the window: the rest of that gesture (including the finger
     * release) has to be swallowed, otherwise the release position is committed as a new value.
     */
    private var swallowUntilUp = false

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                swallowUntilUp = true
                dragging = 0
                setValues(defaultLow, defaultHigh)
                onRangeChanged?.invoke(lowValue, highValue)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
                return true
            }
        }
    )

    init {
        labelPaint.textSize = 10f * resources.displayMetrics.density
        labelPaint.textAlign = Paint.Align.CENTER
    }

    fun setPalette(palette: Palette) {
        this.palette = palette
        labelPaint.color = palette.text
        invalidate()
    }

    /** Sets the values without notifying (used when binding to prefs). */
    fun setValues(low: Float, high: Float) {
        lowValue = low.coerceIn(minValue, maxValue)
        highValue = high.coerceIn(lowValue + step, maxValue)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val h = (56 * density).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    private fun trackLeft(): Float = paddingLeft + thumbRadius()
    private fun trackRight(): Float = width - paddingRight - thumbRadius()
    private fun thumbRadius(): Float = 11f * resources.displayMetrics.density
    private fun trackY(): Float = height - 16f * resources.displayMetrics.density

    private fun valueToX(value: Float): Float {
        val t = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        return trackLeft() + t * (trackRight() - trackLeft())
    }

    private fun xToValue(x: Float): Float {
        val t = ((x - trackLeft()) / (trackRight() - trackLeft()).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = minValue + t * (maxValue - minValue)
        return (Math.round(raw / step) * step).coerceIn(minValue, maxValue)
    }

    override fun onDraw(canvas: Canvas) {
        val p = palette ?: return
        val r = thumbRadius()
        val y = trackY()
        val d = resources.displayMetrics.density

        track.set(trackLeft(), y - 2f * d, trackRight(), y + 2f * d)
        trackPaint.color = p.alpha(p.text, 0.18f)
        trackPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(track, 2f * d, 2f * d, trackPaint)

        val xLow = valueToX(lowValue)
        val xHigh = valueToX(highValue)
        spanPaint.color = p.alpha(p.accent, 0.85f)
        canvas.drawRoundRect(RectF(xLow, y - 2f * d, xHigh, y + 2f * d), 2f * d, 2f * d, spanPaint)

        for ((value, x) in listOf(lowValue to xLow, highValue to xHigh)) {
            thumbPaint.color = p.accent
            canvas.drawCircle(x, y, r, thumbPaint)
            thumbPaint.color = p.bg
            canvas.drawCircle(x, y, r * 0.42f, thumbPaint)
            labelPaint.color = p.text
            canvas.drawText(fmt(value), x, y - r - 5f * d, labelPaint)
        }
    }

    private fun fmt(value: Float): String =
        if (kotlin.math.abs(value - value.toInt()) < 0.05f) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) swallowUntilUp = false
        tapDetector.onTouchEvent(event)
        if (swallowUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                swallowUntilUp = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val xLow = valueToX(lowValue)
                val xHigh = valueToX(highValue)
                dragging = when {
                    kotlin.math.abs(event.x - xLow) <= kotlin.math.abs(event.x - xHigh) -> 1
                    else -> 2
                }
                update(event.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != 0) update(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun update(x: Float) {
        val value = xToValue(x)
        if (dragging == 1) {
            lowValue = value.coerceAtMost(highValue - step)
        } else if (dragging == 2) {
            highValue = value.coerceAtLeast(lowValue + step)
        }
        onRangeChanged?.invoke(lowValue, highValue)
        invalidate()
    }
}

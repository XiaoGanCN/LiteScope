package com.litescope.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * The frequency window shared by the spectrum and waterfall scopes.
 *
 * The visible window is stored as a pair of positions `u0 < u1` inside the *full band* `[0, 1]`,
 * where a position is mapped to a frequency by a continuous blend between a linear and a
 * logarithmic transform ([logBlend] 0 .. 1). Zooming and panning therefore work in position space
 * and behave identically for every scale, and the same [logBlend] value can slide the axis from
 * pure linear to pure log without invalidating the user's zoom.
 *
 * A small lookup table (rebuilt only when the band or the blend changes) converts positions to
 * frequencies and back, which also makes the inverse mapping of a blended axis exact and cheap.
 */
class FreqAxis {

    /** Lower edge of the band. */
    var minHz: Float = 20f
        private set

    /** Upper edge of the band (usually the Nyquist frequency). */
    var maxHz: Float = 24000f
        private set

    /** 0 = pure linear axis, 1 = pure logarithmic axis. */
    var logBlend: Float = 1f
        private set

    private var u0 = 0f
    private var u1 = 1f
    private var lut = FloatArray(0)
    private var lutValid = false

    /** Incremented on every change; views can use it as a cheap "did the axis move" check. */
    var version: Int = 0
        private set

    // ------------------------------------------------------------------ setup

    /**
     * Points the axis at a (possibly new) band and scale. The current zoom is preserved unless the
     * band shrank so much that the window no longer fits.
     */
    fun configure(maxHz: Float, logBlend: Float, resetIfInvalid: Boolean = true) {
        val newMax = maxHz.coerceAtLeast(100f)
        val newBlend = logBlend.coerceIn(0f, 1f)
        val newMin = if (newBlend > 0.999f) LOG_MIN_HZ else 0f
        val bandChanged = newMax != this.maxHz || newBlend != this.logBlend || newMin != this.minHz
        this.maxHz = newMax
        this.logBlend = newBlend
        this.minHz = newMin
        if (bandChanged) {
            lut = FloatArray(LUT_SIZE + 1)
            lutValid = false
            if (resetIfInvalid && (u1 <= u0 || u1 > 1.0001f)) reset()
        }
        if (!lutValid) {
            buildLut()
            version++
        }
    }

    /** Shows the whole band. */
    fun reset() {
        u0 = 0f
        u1 = 1f
        version++
    }

    /** Sets the visible window from a frequency range; used when restoring/syncing an axis. */
    fun setRange(start: Float, end: Float) {
        if (end <= start) return
        var a = hzToPos(start)
        var b = hzToPos(end)
        if (b - a < MIN_SPAN_POS) {
            val mid = (a + b) * 0.5f
            a = mid - MIN_SPAN_POS / 2f
            b = mid + MIN_SPAN_POS / 2f
        }
        clampWindow(a, b)
        version++
    }

    /** Copies the visible window from another axis. */
    fun copyFrom(other: FreqAxis) {
        u0 = other.u0
        u1 = other.u1
        version++
    }

    // ------------------------------------------------------------------ window

    val startHz: Float get() = posToHz(u0)

    val endHz: Float get() = posToHz(u1)

    val spanHz: Float get() = (endHz - startHz).coerceAtLeast(0f)

    val isFullBand: Boolean get() = u0 <= 0.0001f && u1 >= 0.9999f

    /** Fraction of the band currently visible, 0..1. */
    val zoomFraction: Float get() = (u1 - u0).coerceIn(0f, 1f)

    fun hzToX(hz: Float, width: Float): Float {
        val span = u1 - u0
        if (span <= 0f) return 0f
        return (hzToPos(hz) - u0) / span * width
    }

    fun xToHz(x: Float, width: Float): Float {
        if (width <= 0f) return startHz
        return posToHz(u0 + (x / width).coerceIn(0f, 1f) * (u1 - u0))
    }

    /**
     * Zooms by [factor] (< 1 zooms in) keeping the frequency under the gesture focus pinned at the
     * same relative position.
     */
    fun zoomAt(focusHz: Float, factor: Float) {
        if (factor <= 0f) return
        val span = u1 - u0
        val focusPos = hzToPos(focusHz)
        val t = if (span > 0f) ((focusPos - u0) / span).coerceIn(0f, 1f) else 0.5f
        val newSpan = (span * factor).coerceIn(MIN_SPAN_POS, 1f)
        var a = focusPos - t * newSpan
        var b = a + newSpan
        clampWindow(a, b)
        version++
    }

    /** Pans by a fraction of the visible span (positive = towards higher frequencies). */
    fun panByFraction(fraction: Float) {
        val span = u1 - u0
        val delta = span * fraction
        clampWindow(u0 + delta, u1 + delta)
        version++
    }

    private fun clampWindow(a: Float, b: Float) {
        var start = a
        var end = b
        var span = (end - start).coerceIn(MIN_SPAN_POS, 1f)
        if (start < 0f) {
            start = 0f
            end = span
        }
        if (end > 1f) {
            end = 1f
            start = end - span
        }
        if (start < 0f) {
            start = 0f
            end = span.coerceAtMost(1f)
        }
        u0 = start
        u1 = end
    }

    // ------------------------------------------------------------------ mapping

    private fun buildLut() {
        if (lut.size != LUT_SIZE + 1) lut = FloatArray(LUT_SIZE + 1)
        for (i in 0..LUT_SIZE) {
            lut[i] = transform(i.toFloat() / LUT_SIZE)
        }
        lutValid = true
    }

    /** Blended transform from a normalised position to a frequency. Monotonic by construction. */
    private fun transform(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        val linear = minHz + (maxHz - minHz) * t
        if (logBlend <= 0.0001f) return linear
        val lo = ln(minHz + 1f)
        val hi = ln(maxHz + 1f)
        val log = exp(lo + (hi - lo) * t) - 1f
        return linear + (log - linear) * logBlend
    }

    private fun posToHz(u: Float): Float {
        if (!lutValid) buildLut()
        val p = (u.coerceIn(0f, 1f)) * LUT_SIZE
        val i = p.toInt().coerceIn(0, LUT_SIZE - 1)
        val f = p - i
        return lut[i] + (lut[i + 1] - lut[i]) * f
    }

    /** Inverse of [posToHz] by binary search over the monotonic table. */
    private fun hzToPos(hz: Float): Float {
        if (!lutValid) buildLut()
        val target = hz.coerceIn(lut[0], lut[LUT_SIZE])
        var lo = 0
        var hi = LUT_SIZE
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (lut[mid] <= target) lo = mid else hi = mid
        }
        val a = lut[lo]
        val b = lut[hi]
        val f = if (b > a) (target - a) / (b - a) else 0f
        return (lo + f) / LUT_SIZE
    }

    companion object {
        /** Smallest visible window, as a fraction of the band. */
        const val MIN_SPAN_POS = 0.0008f
        private const val LUT_SIZE = 1024
        private const val LOG_MIN_HZ = 10f
    }
}

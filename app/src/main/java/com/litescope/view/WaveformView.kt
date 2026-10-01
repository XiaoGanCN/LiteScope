package com.litescope.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.litescope.core.AudioBus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Time domain scope.
 *
 * Three stereo layouts: **overlay** (both channels in one lane), **split** (left and right in
 * separate lanes) and **sum** (the two channels literally added into a single trace). Zoomed in it
 * draws the real sample polyline; further out it draws a min/max envelope per pixel column in a
 * single pass over the ring buffer.
 *
 * Gestures: pinch = time base, horizontal drag = pan, vertical drag = gain, tap = freeze/resume,
 * double tap = reset, long press = cycle the stereo layout.
 */
@SuppressLint("ClickableViewAccessibility") // gesture surface: touches drive zoom/pan, not clicks
class WaveformView(context: Context) : ScopeView(context, TOOL_ID) {

    private var envelope = FloatArray(0)
    private var envColumns = 0
    private var raw = FloatArray(0)
    private var triggerBuf = FloatArray(0)
    private var freqBuf = FloatArray(0)

    private var panFrames = 0L
    private var triggerLock = -1L

    /** Freeze state: [frozenNewest] pins the display to a point in the ring. */
    private var frozen = false
    private var frozenNewest = -1L

    private val laneRect = RectF()
    private val tracePath = Path()
    private val tracePathB = Path()

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var dragLane = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = if (detector.scaleFactor > 0f) detector.scaleFactor else 1f
                prefs.waveMsPerDiv = (prefs.waveMsPerDiv / f).coerceIn(MIN_MS_DIV, MAX_MS_DIV)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleFreeze()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                prefs.waveStereoMode = (prefs.waveStereoMode + 1) % MODE_COUNT
                invalidate()
            }
        }
    )

    /** Freezes/unfreezes the trace: the display stops following the live edge. */
    fun toggleFreeze() {
        if (frozen) {
            frozen = false
            frozenNewest = -1L
        } else {
            frozen = true
            frozenNewest = hub.bus.totalFrames
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                lastY = event.y
                dragLane = if (prefs.waveStereoMode == MODE_SPLIT && event.y < height / 2f) 0 else 1
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (abs(dx) > 0.5f) {
                        val rate = hub.bus.sampleRate.coerceAtLeast(8000)
                        val sweep = windowFrames(rate)
                        val delta = (dx / max(1f, width.toFloat()) * sweep).toLong()
                        panFrames = (panFrames + delta).coerceAtLeast(0L)
                        invalidate()
                    } else if (abs(dy) > 0.5f) {
                        val deltaDb = -dy * 0.35f
                        when {
                            prefs.waveLinkGain -> {
                                val next = (prefs.waveGainDbL + deltaDb).coerceIn(-40f, 60f)
                                prefs.waveGainDbL = next
                                prefs.waveGainDbR = next
                            }
                            prefs.waveStereoMode == MODE_SPLIT && dragLane == 0 ->
                                prefs.waveGainDbL = (prefs.waveGainDbL + deltaDb).coerceIn(-40f, 60f)
                            prefs.waveStereoMode == MODE_SPLIT ->
                                prefs.waveGainDbR = (prefs.waveGainDbR + deltaDb).coerceIn(-40f, 60f)
                            else ->
                                prefs.waveGainDbL = (prefs.waveGainDbL + deltaDb).coerceIn(-40f, 60f)
                        }
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun resetView() {
        panFrames = 0
        triggerLock = -1
        frozen = false
        frozenNewest = -1L
        prefs.waveMsPerDiv = 5f
        prefs.waveGainDbL = 0f
        prefs.waveGainDbR = 0f
        invalidate()
    }

    private fun windowFrames(rate: Int): Int =
        ((prefs.waveMsPerDiv / 1000f) * rate * DIVISIONS).toInt().coerceIn(16, 1 shl 22)

    /** One drawable trace: envelope slot, scale applied to it and which channel's gain to use. */
    private class Lane(val index: Int, val scale: Float, val channel: Int)

    private fun lanes(): List<Lane> = when (prefs.waveStereoMode) {
        MODE_SPLIT -> listOf(Lane(0, 1f, 0), Lane(2, 1f, 1))
        MODE_SUM -> listOf(Lane(4, 1f, 0))
        else -> when (prefs.waveSource) {
            1 -> listOf(Lane(0, 1f, 0))
            2 -> listOf(Lane(2, 1f, 1))
            3 -> listOf(Lane(4, 0.5f, 0))
            4 -> listOf(Lane(6, 0.5f, 0))
            else -> listOf(Lane(0, 1f, 0), Lane(2, 1f, 1))
        }
    }

    override fun drawScope(canvas: Canvas) {
        drawBackdrop(canvas)
        val p = palette
        val rate = hub.bus.sampleRate
        val showLabels = width > dp(150f) && height > dp(70f)
        // The wave scope has no vertical scale of its own, so it must not reserve a left gutter.
        val left = dp(ScopeMetrics.PAD_DP)
        val top = dp(3f)
        val right = width - dp(3f)
        val bottom = height - (if (showLabels) dp(13f) else dp(3f))
        if (right - left < 8f || bottom - top < 8f) return

        if (rate <= 0 || hub.bus.capacity == 0) {
            label(canvas, "no capture", left + dp(6f), top + dp(16f), p.dim)
            return
        }

        val sweep = windowFrames(rate)
        val liveNewest = hub.bus.totalFrames
        val newest = if (frozen && frozenNewest > 0) min(frozenNewest, liveNewest) else liveNewest
        val oldest = hub.bus.oldestFrame
        val columns = min((right - left).toInt().coerceAtLeast(8), MAX_COLUMNS)
        val plotWidth = right - left
        val split = prefs.waveStereoMode == MODE_SPLIT
        val laneCount = if (split) 2 else 1

        if (newest <= 0 || newest - oldest < sweep) {
            label(canvas, "buffering…", left + dp(6f), top + dp(16f), p.dim)
            return
        }

        val start = resolveStartFrame(newest, oldest, sweep)

        for (lane in 0 until laneCount) {
            val laneTop = top + (bottom - top) * lane / laneCount
            val laneBottom = top + (bottom - top) * (lane + 1) / laneCount
            laneRect.set(left, laneTop, right, laneBottom)
            drawLaneGrid(canvas, laneRect)
        }

        val useRaw = sweep <= RAW_LIMIT
        var rawGot = 0
        var envelopeReady = false
        if (useRaw) {
            if (raw.size < sweep * 2) raw = FloatArray(sweep * 2)
            rawGot = hub.bus.copyStereo(raw, start, sweep)
        } else {
            if (envColumns != columns || envelope.size < columns * AudioBus.COLUMNS_STRIDE) {
                envColumns = columns
                envelope = FloatArray(columns * AudioBus.COLUMNS_STRIDE)
            }
            hub.bus.stereoEnvelope(envelope, start, sweep, columns)
            envelopeReady = envelope.size >= columns * AudioBus.COLUMNS_STRIDE
        }

        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        if (useRaw) {
            if (rawGot > 1) drawRaw(canvas, left, plotWidth, top, bottom, rawGot, split)
        } else if (envelopeReady) {
            drawEnvelope(canvas, left, plotWidth, top, bottom, columns, split)
        }
        canvas.restore()

        drawLaneLabels(canvas, left, right, top, bottom, laneCount, split)
        drawTriggerMarker(canvas, left, right, top, bottom, split)
        drawReadout(canvas, newest, rate, left, top, right, bottom, showLabels, useRaw, rawGot, columns)
        if (frozen) drawHoldBadge(canvas, top, right)
    }

    private fun drawLaneGrid(canvas: Canvas, rect: RectF) {
        applyGridColors()
        val midY = rect.centerY()
        canvas.drawLine(rect.left, midY, rect.right, midY, gridMajorPaint)
        canvas.drawLine(
            rect.left, rect.top + rect.height() * 0.25f,
            rect.right, rect.top + rect.height() * 0.25f, gridPaint
        )
        canvas.drawLine(
            rect.left, rect.top + rect.height() * 0.75f,
            rect.right, rect.top + rect.height() * 0.75f, gridPaint
        )
        for (i in 1 until DIVISIONS) {
            val x = rect.left + rect.width() * i / DIVISIONS
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
        }
    }

    private fun drawLaneLabels(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        laneCount: Int,
        split: Boolean
    ) {
        val p = palette
        val text = when {
            split -> null
            prefs.waveStereoMode == MODE_SUM -> "L+R"
            else -> when (prefs.waveSource) {
                1 -> "L"
                2 -> "R"
                3 -> "MID"
                4 -> "SIDE"
                else -> "L / R"
            }
        }
        if (text != null) {
            label(canvas, text, left + dp(4f), top + dp(11f), p.dim)
            return
        }
        for (lane in 0 until laneCount) {
            val laneTop = top + (bottom - top) * lane / laneCount
            val laneBottom = top + (bottom - top) * (lane + 1) / laneCount
            canvas.drawLine(left, laneTop, right, laneTop, gridPaint)
            label(
                canvas,
                if (lane == 0) "L" else "R",
                left + dp(4f),
                laneTop + dp(11f),
                if (lane == 0) p.accent else p.accentAlt
            )
            label(
                canvas,
                String.format(
                    java.util.Locale.US, "%+.1f dB",
                    if (lane == 0) prefs.waveGainDbL else prefs.waveGainDbR
                ),
                right - dp(4f),
                laneBottom - dp(4f),
                p.dim,
                Paint.Align.RIGHT
            )
        }
    }

    private fun drawTriggerMarker(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        split: Boolean
    ) {
        if (prefs.waveTriggerMode == TRIGGER_FREE) return
        val laneTop = top
        val laneBottom = if (split) (top + bottom) / 2f else bottom
        val midY = (laneTop + laneBottom) / 2f
        val halfH = (laneBottom - laneTop) / 2f * LANE_FILL
        val y = midY - prefs.waveTriggerLevel * halfH
        applyGridColors()
        canvas.drawLine(left, y, right, y, gridMajorPaint)
        label(
            canvas,
            String.format(java.util.Locale.US, "trig %+.2f", prefs.waveTriggerLevel),
            right - dp(4f),
            y - dp(3f),
            palette.dim,
            Paint.Align.RIGHT
        )
    }

    private fun drawHoldBadge(canvas: Canvas, top: Float, right: Float) {
        val text = "HOLD"
        val w = labelWidth(text) + dp(12f)
        drawPanel(canvas, right - w - dp(4f), top + dp(3f), right - dp(4f), top + dp(17f))
        label(canvas, text, right - w + dp(2f), top + dp(14f), palette.warn)
    }

    // ------------------------------------------------------------------ traces

    private fun drawEnvelope(
        canvas: Canvas,
        left: Float,
        plotWidth: Float,
        top: Float,
        bottom: Float,
        columns: Int,
        split: Boolean
    ) {
        if (envelope.size < columns * AudioBus.COLUMNS_STRIDE) return
        val p = palette
        val laneCount = if (split) 2 else 1
        val step = plotWidth / columns
        val specs = lanes()
        for (lane in 0 until laneCount) {
            val laneTop = top + (bottom - top) * lane / laneCount
            val laneBottom = top + (bottom - top) * (lane + 1) / laneCount
            val midY = (laneTop + laneBottom) / 2f
            val halfH = (laneBottom - laneTop) / 2f * LANE_FILL

            canvas.save()
            canvas.clipRect(left, laneTop, left + plotWidth, laneBottom)
            val laneSpecs = if (split) listOf(specs[lane]) else specs
            for (spec in laneSpecs) {
                val gain = gainFactor(if (spec.channel == 1) prefs.waveGainDbR else prefs.waveGainDbL)
                val dc = envelopeDc(columns, spec.index) * spec.scale
                tracePath.reset()
                for (c in 0 until columns) {
                    val base = c * AudioBus.COLUMNS_STRIDE
                    val mn = (envelope[base + spec.index] * spec.scale - dc) * gain
                    val mx = (envelope[base + spec.index + 1] * spec.scale - dc) * gain
                    val x = left + c * step
                    tracePath.moveTo(x, midY - clampFs(mn) * halfH)
                    tracePath.lineTo(x, midY - clampFs(mx) * halfH)
                }
                val color = if (spec.channel == 1) p.accentAlt else p.accent
                if (laneSpecs.size > 1 && spec.channel == 1) {
                    secondaryPaint.color = p.alpha(color, 0.9f)
                    canvas.drawPath(tracePath, secondaryPaint)
                } else {
                    tracePaint.color = color
                    drawTraced(canvas, tracePath, tracePaint)
                }
            }
            canvas.restore()
        }
    }

    private fun drawRaw(
        canvas: Canvas,
        left: Float,
        plotWidth: Float,
        top: Float,
        bottom: Float,
        frames: Int,
        split: Boolean
    ) {
        val p = palette
        val laneCount = if (split) 2 else 1
        val specs = lanes()
        val denom = (frames - 1).toFloat()
        for (lane in 0 until laneCount) {
            val laneTop = top + (bottom - top) * lane / laneCount
            val laneBottom = top + (bottom - top) * (lane + 1) / laneCount
            val midY = (laneTop + laneBottom) / 2f
            val halfH = (laneBottom - laneTop) / 2f * LANE_FILL

            canvas.save()
            canvas.clipRect(left, laneTop, left + plotWidth, laneBottom)
            val laneSpecs = if (split) listOf(specs[lane]) else specs
            for ((order, spec) in laneSpecs.withIndex()) {
                val gain = gainFactor(if (spec.channel == 1) prefs.waveGainDbR else prefs.waveGainDbL)
                val dc = if (prefs.waveAcCoupling) rawMean(frames, spec.index) * spec.scale else 0f
                val path = if (order == 0) tracePath else tracePathB
                path.reset()
                for (i in 0 until frames) {
                    val x = left + plotWidth * i / denom
                    val value = (rawValue(i, spec.index) * spec.scale - dc) * gain
                    val y = midY - clampFs(value) * halfH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val color = if (spec.channel == 1) p.accentAlt else p.accent
                if (order == 0) {
                    tracePaint.color = color
                    drawTraced(canvas, path, tracePaint)
                } else {
                    secondaryPaint.color = p.alpha(color, 0.9f)
                    canvas.drawPath(path, secondaryPaint)
                }
            }
            canvas.restore()
        }
    }

    private fun rawValue(i: Int, index: Int): Float {
        val l = raw[i * 2]
        val r = raw[i * 2 + 1]
        return when (index) {
            0 -> l
            2 -> r
            4 -> l + r
            else -> l - r
        }
    }

    private fun rawMean(frames: Int, index: Int): Float {
        var sum = 0f
        for (i in 0 until frames) sum += rawValue(i, index)
        return if (frames > 0) sum / frames else 0f
    }

    private fun envelopeDc(columns: Int, index: Int): Float {
        var sum = 0f
        for (c in 0 until columns) {
            val base = c * AudioBus.COLUMNS_STRIDE
            sum += (envelope[base + index] + envelope[base + index + 1]) * 0.5f
        }
        return sum / max(1, columns)
    }

    // ------------------------------------------------------------------ readouts

    private fun drawReadout(
        canvas: Canvas,
        newest: Long,
        rate: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        showLabels: Boolean,
        useRaw: Boolean,
        rawGot: Int,
        columns: Int
    ) {
        if (!prefs.showReadout) return
        val p = palette
        val freq = estimateFrequency(newest, rate)
        val levels = hub.levels.get()
        val modeLabel = when (prefs.waveStereoMode) {
            MODE_SPLIT -> "split"
            MODE_SUM -> "sum"
            else -> "overlay"
        }
        val head = String.format(
            java.util.Locale.US,
            "%.2f ms/div · %.1f ms · %s · %s%s",
            prefs.waveMsPerDiv,
            prefs.waveMsPerDiv * DIVISIONS,
            if (prefs.waveAcCoupling) "AC" else "DC",
            modeLabel,
            if (freq > 0f) String.format(java.util.Locale.US, " · ≈%.1f Hz", freq) else ""
        )
        val w = labelWidth(head) + dp(10f)
        drawPanel(canvas, left + dp(3f), bottom - dp(16f), min(left + dp(3f) + w, right), bottom - dp(2f))
        label(canvas, head, left + dp(7f), bottom - dp(5f), p.text)

        if (!prefs.waveShowMeasurements || levels == null || !showLabels) return
        val primary = lanes().first()
        val vpp = when {
            useRaw && rawGot > 1 -> vppFromRaw(rawGot, primary.index, primary.scale)
            !useRaw && envelope.size >= columns * AudioBus.COLUMNS_STRIDE ->
                vppFromEnvelope(columns, primary.index, primary.scale)
            else -> 0f
        }
        val rms = when {
            prefs.waveStereoMode == MODE_SPLIT -> max(levels.rmsL, levels.rmsR)
            prefs.waveStereoMode == MODE_SUM -> min(1.5f, levels.rmsL + levels.rmsR)
            else -> when (prefs.waveSource) {
                1 -> levels.rmsL
                2 -> levels.rmsR
                3 -> levels.midRms
                4 -> levels.sideRms
                else -> (levels.rmsL + levels.rmsR) * 0.5f
            }
        }
        val text = String.format(
            java.util.Locale.US,
            "Vpp %.2f FS · RMS %.1f dBFS · peak %.1f dBFS",
            vpp,
            20f * kotlin.math.log10(max(rms, 1e-6f)),
            20f * kotlin.math.log10(max(max(levels.peakL, levels.peakR), 1e-6f))
        )
        label(canvas, text, left + dp(4f), top + dp(11f), p.dim)
    }

    private fun vppFromEnvelope(columns: Int, index: Int, scale: Float): Float {
        if (envelope.size < columns * AudioBus.COLUMNS_STRIDE) return 0f
        var vpp = 0f
        for (c in 0 until columns) {
            val base = c * AudioBus.COLUMNS_STRIDE
            val d = (envelope[base + index + 1] - envelope[base + index]) * scale
            if (d > vpp) vpp = d
        }
        return vpp
    }

    private fun vppFromRaw(frames: Int, index: Int, scale: Float): Float {
        if (raw.size < frames * 2) return 0f
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until frames) {
            val v = rawValue(i, index) * scale
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        return (maxV - minV).coerceAtLeast(0f)
    }

    private fun estimateFrequency(newest: Long, rate: Int): Float {
        val count = min(FREQ_WINDOW.toLong(), newest).toInt()
        if (count < 64) return 0f
        if (freqBuf.size < count * 2) freqBuf = FloatArray(count * 2)
        val buf = freqBuf
        val got = hub.bus.copyStereo(buf, newest - count, count)
        if (got < 64) return 0f
        var mean = 0f
        for (i in 0 until got) mean += (buf[i * 2] + buf[i * 2 + 1]) * 0.5f
        mean /= got
        var crossings = 0
        var last = (buf[0] + buf[1]) * 0.5f - mean
        for (i in 1 until got) {
            val v = (buf[i * 2] + buf[i * 2 + 1]) * 0.5f - mean
            if (last < 0f && v >= 0f) crossings++
            last = v
        }
        if (crossings < 2) return 0f
        return crossings.toFloat() * rate / got
    }

    // ------------------------------------------------------------------ window / trigger

    private fun resolveStartFrame(newest: Long, oldest: Long, sweep: Int): Long {
        val mode = prefs.waveTriggerMode
        var start: Long
        if (mode == TRIGGER_FREE) {
            start = newest - sweep - panFrames
        } else {
            val lock = findTrigger(newest, oldest, sweep)
            if (lock >= 0) triggerLock = lock
            start = if (triggerLock >= 0) triggerLock - sweep / 5 else newest - sweep
            if (mode == TRIGGER_NORMAL && triggerLock >= 0) {
                return start.coerceAtLeast(oldest)
            }
        }
        if (start + sweep > newest) start = newest - sweep
        if (start < oldest) start = oldest
        return start
    }

    private fun findTrigger(newest: Long, oldest: Long, sweep: Int): Long {
        val available = (newest - oldest).toInt()
        val search = min(min(sweep, MAX_TRIGGER_SEARCH), available)
        if (search < 8) return -1L
        if (triggerBuf.size < search * 2) triggerBuf = FloatArray(search * 2)
        val searchStart = newest - search
        val got = hub.bus.copyStereo(triggerBuf, searchStart, search)
        if (got < 8) return -1L
        val level = prefs.waveTriggerLevel
        val rising = prefs.waveTriggerEdge == 0
        val src = prefs.waveTriggerSource
        var i = got - 1
        while (i >= 1) {
            val a = triggerSample(i - 1, src)
            val b = triggerSample(i, src)
            val crossed = if (rising) (a < level && b >= level) else (a > level && b <= level)
            if (crossed) return searchStart + i
            i--
        }
        return -1L
    }

    private fun triggerSample(index: Int, source: Int): Float = when (source) {
        1 -> triggerBuf[index * 2]
        2 -> triggerBuf[index * 2 + 1]
        else -> (triggerBuf[index * 2] + triggerBuf[index * 2 + 1]) * 0.5f
    }

    private fun gainFactor(db: Float): Float = 10f.pow(db / 20f)

    private fun clampFs(v: Float): Float = v.coerceIn(-2.5f, 2.5f)

    companion object {
        const val TOOL_ID = "waveform"

        /** Stereo layouts: overlay both channels, split them, or show the literal sum. */
        const val MODE_OVERLAY = 0
        const val MODE_SPLIT = 1
        const val MODE_SUM = 2
        const val MODE_COUNT = 3

        private const val DIVISIONS = 10
        private const val LANE_FILL = 0.92f
        private const val MAX_COLUMNS = 1400
        private const val RAW_LIMIT = 8192
        private const val MAX_TRIGGER_SEARCH = 32768
        private const val FREQ_WINDOW = 8192
        private const val TRIGGER_NORMAL = 1
        private const val TRIGGER_FREE = 2
        private const val MIN_MS_DIV = 0.02f
        private const val MAX_MS_DIV = 200f
    }
}

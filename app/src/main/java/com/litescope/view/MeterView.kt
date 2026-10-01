package com.litescope.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Peak / RMS meters.
 *
 * Design language (see the sketches in `ideas/`):
 *  * a latching red clip rectangle sits at the top of each channel's bar
 *  * each channel gets a rounded value pill: in the vertical layout the two pills sit side by side
 *    under the bars, i.e. between the two meters, and in the horizontal layout each sits at the head
 *    of its own bar
 *  * a dedicated loudness text block (peak / RMS / crest / balance / correlation) is drawn at the
 *    bottom for the vertical layout and at the left for the horizontal one
 *
 * The layout adapts to very narrow windows: the scale and the text block drop out first, then the
 * pills switch to a compact form, so a slim meter window still shows bars and levels.
 */
class MeterView(context: Context) : ScopeView(context, TOOL_ID) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val boxRect = RectF()

    private var gradient: LinearGradient? = null
    private var gradientKey = ""

    /** Smoothed display values: rmsL, rmsR, peakL, peakR, rmsSum, peakSum. */
    private val display = FloatArray(6)
    private var lastFrameAt = 0L

    override fun drawScope(canvas: Canvas) {
        drawBackdrop(canvas)
        val p = palette
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 20f || h < 20f) return

        val levels = hub.levels.get()
        if (levels == null) {
            label(canvas, "no signal", dp(6f), dp(16f), p.dim)
            return
        }
        updateBallistics(levels)

        val vertical = h >= w * 0.72f
        if (vertical) drawVertical(canvas, w, h, levels) else drawHorizontal(canvas, w, h, levels)
    }

    /** Attack/release smoothing so the bars move like a real meter instead of flickering. */
    private fun updateBallistics(levels: com.litescope.dsp.LevelFrame) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else ((now - lastFrameAt) / 1000f).coerceIn(0.001f, 0.5f)
        lastFrameAt = now
        val attack = 1f - exp(-dt / 0.025f)
        val release = 1f - exp(-dt / 0.32f)
        val targets = floatArrayOf(
            levels.rmsL, levels.rmsR, levels.peakL, levels.peakR, levels.sumRms, levels.sumPeak
        )
        for (i in 0..5) {
            val target = targets[i]
            val k = if (target > display[i]) attack else release
            display[i] += (target - display[i]) * k
        }
    }

    /** One drawable meter: left, right, or the literal L + R sum. */
    private class Channel(val label: String, val rmsIndex: Int, val peakIndex: Int, val accentAlt: Boolean)

    private fun channels(): List<Channel> = when (prefs.meterLayout) {
        1 -> listOf(Channel("L+R", 4, 5, false))
        2 -> listOf(Channel("L", 0, 2, false), Channel("R", 1, 3, true), Channel("L+R", 4, 5, false))
        else -> listOf(Channel("L", 0, 2, false), Channel("R", 1, 3, true))
    }

    private fun Channel.accent(p: Palette): Int = if (accentAlt) p.accentAlt else p.accent

    private fun Channel.rms(): Float = display[rmsIndex]

    private fun Channel.peak(): Float = display[peakIndex]

    private fun Channel.hold(levels: com.litescope.dsp.LevelFrame): Float = when (label) {
        "L" -> levels.holdL
        "R" -> levels.holdR
        else -> max(levels.holdL, levels.holdR)
    }

    // ------------------------------------------------------------------ vertical layout

    private fun drawVertical(canvas: Canvas, w: Float, h: Float, levels: com.litescope.dsp.LevelFrame) {
        val p = palette
        val pad = dp(5f)
        val showScale = prefs.meterShowScale && w > dp(112f)
        // Split L/R puts the dB scale in the gap between the two bars; combined/both keep it on the
        // right hand side.
        val split = prefs.meterLayout == 0
        val rightGutter = if (showScale && !split) dp(27f) else 0f
        val headerH = dp(13f)
        val clipH = dp(8f)
        val labelMode = prefs.meterLabelPosition
        val pillH = when {
            labelMode != LABEL_UNDER -> 0f
            w > dp(96f) -> dp(24f)
            else -> dp(18f)
        }
        val panel = panelLayout(w - pad * 2)
        val panelHeight = panelHeight(panel)

        val top = pad
        val barsTop = top + headerH + clipH + dp(2f)
        val barsBottom = h - pad - pillH - (if (pillH > 0f) dp(4f) else 0f) -
            (if (panelHeight > 0f) panelHeight + dp(4f) else 0f)
        if (barsBottom - barsTop < dp(16f)) {
            drawSlimVertical(canvas, w, h, levels)
            return
        }

        val list = channels()
        val areaLeft = pad
        val areaRight = w - pad - rightGutter
        val areaWidth = (areaRight - areaLeft).coerceAtLeast(dp(16f))
        val gap = if (split && showScale) dp(32f) else dp(5f)
        val slot = (areaWidth - gap * (list.size - 1)) / list.size
        val barWidth = if (prefs.meterSlim) min(dp(24f), slot) else slot
        val groupWidth = barWidth * list.size + gap * (list.size - 1)
        val groupLeft = areaLeft + (areaWidth - groupWidth) / 2f

        if (showScale) {
            if (split) {
                drawScaleInGap(canvas, groupLeft + barWidth, gap, barsTop, barsBottom)
            } else {
                drawScaleOnRight(canvas, w - pad, barsTop, barsBottom)
            }
        }

        list.forEachIndexed { index, channel ->
            val x = groupLeft + index * (barWidth + gap)
            val center = x + barWidth / 2f
            val accent = channel.accent(p)
            val peak = channel.peak()
            val rms = channel.rms()

            // Channel letter only: the numeric values live in the pills below, between the meters.
            label(
                canvas,
                channel.label,
                center,
                top + dp(9f),
                accent,
                Paint.Align.CENTER
            )
            drawClipRect(canvas, x, top + headerH, barWidth, clipH, levels.clippedAt)

            trackPaint.color = if (p.isLight) 0x14000000 else 0x33000000
            rect.set(x, barsTop, x + barWidth, barsBottom)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), trackPaint)

            if (rms > 1e-5f) {
                barPaint.shader = meterGradient(barsTop, barsBottom)
                rect.set(x, dbToY(rms, barsTop, barsBottom), x + barWidth, barsBottom)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), barPaint)
                barPaint.shader = null
            }

            val peakY = dbToY(peak, barsTop, barsBottom)
            linePaint.color = p.text
            linePaint.strokeWidth = dp(2f)
            canvas.drawLine(x, peakY, x + barWidth, peakY, linePaint)

            val holdY = dbToY(channel.hold(levels), barsTop, barsBottom)
            linePaint.color = p.alpha(p.warn, 0.95f)
            linePaint.strokeWidth = dp(1.5f)
            canvas.drawLine(x, holdY, x + barWidth, holdY, linePaint)
            canvas.drawRect(center - dp(2f), holdY - dp(3f), center + dp(2f), holdY, linePaint)
        }

        drawVerticalLabels(canvas, list, w, groupLeft, barWidth, gap, barsTop, barsBottom, labelMode)

        if (panelHeight > 0f) {
            drawLoudness(canvas, pad, h - pad - panelHeight, w - pad, h - pad, panel, levels)
        }
    }

    /**
     * Per-channel value text placement in the vertical layout:
     *  * UNDER   - a centred row of pills below the bars (the default)
     *  * ON_BAR  - a badge over the middle of each bar
     *  * BETWEEN - the values centred on the gap between the two meters
     *  * OUTSIDE - left of the left bar and right of the right bar
     */
    private fun drawVerticalLabels(
        canvas: Canvas,
        list: List<Channel>,
        w: Float,
        groupLeft: Float,
        barWidth: Float,
        gap: Float,
        barsTop: Float,
        barsBottom: Float,
        mode: Int
    ) {
        val p = palette
        val midY = (barsTop + barsBottom) / 2f
        val badgeH = dp(18f)
        val groupWidth = barWidth * list.size + gap * (list.size - 1)
        val groupCenter = groupLeft + groupWidth / 2f

        when (mode) {
            LABEL_ON_BAR -> {
                list.forEachIndexed { index, channel ->
                    val x = groupLeft + index * (barWidth + gap)
                    drawValueBadge(
                        canvas,
                        x + dp(1f),
                        midY - badgeH / 2f,
                        x + barWidth - dp(1f),
                        midY + badgeH / 2f,
                        dbText(channel.peak()),
                        channel.accent(p)
                    )
                }
            }
            else -> drawUnderRow(canvas, list, w, barsBottom + dp(4f), if (w > dp(96f)) dp(24f) else dp(18f))
        }
    }

    /** Centred row of pills under the bars. */
    private fun drawUnderRow(
        canvas: Canvas,
        list: List<Channel>,
        w: Float,
        top: Float,
        height: Float
    ) {
        val p = palette
        val gap = dp(5f)
        val groupCap = min(w - dp(10f), dp(168f))
        val pillWidth = (groupCap - gap * (list.size - 1)) / list.size
        val left = (w - groupCap) / 2f
        list.forEachIndexed { index, channel ->
            val x = left + index * (pillWidth + gap)
            drawValuePill(
                canvas,
                x,
                top,
                x + pillWidth,
                top + height,
                dbText(channel.peak()),
                channel.accent(p),
                height >= dp(22f)
            )
        }
    }

    /** Ultra slim window: bars plus tiny clip strip, everything else dropped. */
    private fun drawSlimVertical(canvas: Canvas, w: Float, h: Float, levels: com.litescope.dsp.LevelFrame) {
        val p = palette
        val pad = dp(4f)
        val top = pad
        val bottom = h - pad
        if (bottom - top < dp(12f)) return
        val list = channels()
        val gap = dp(4f)
        val barWidth = ((w - pad * 2 - gap * (list.size - 1)) / list.size).coerceAtLeast(dp(3f))
        drawClipRect(canvas, pad, pad, w - pad * 2, dp(7f), levels.clippedAt)
        val barsTop = pad + dp(9f)
        list.forEachIndexed { index, channel ->
            val x = pad + index * (barWidth + gap)
            trackPaint.color = if (p.isLight) 0x14000000 else 0x33000000
            rect.set(x, barsTop, x + barWidth, bottom)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), trackPaint)
            val rms = channel.rms()
            if (rms > 1e-5f) {
                barPaint.shader = meterGradient(barsTop, bottom)
                rect.set(x, dbToY(rms, barsTop, bottom), x + barWidth, bottom)
                canvas.drawRoundRect(rect, dp(2f), dp(2f), barPaint)
                barPaint.shader = null
            }
            linePaint.color = p.text
            linePaint.strokeWidth = dp(1.5f)
            val peakY = dbToY(channel.peak(), barsTop, bottom)
            canvas.drawLine(x, peakY, x + barWidth, peakY, linePaint)
        }
    }

    /** dB scale drawn inside the gap between the two split bars, with a tick against each bar. */
    private fun drawScaleInGap(canvas: Canvas, gapLeft: Float, gap: Float, top: Float, bottom: Float) {
        val p = palette
        applyGridColors()
        val gapRight = gapLeft + gap
        for (db in scaleSteps()) {
            val y = dbToY(10f.pow(db / 20f), top, bottom)
            canvas.drawLine(gapLeft, y, gapLeft + dp(3f), y, gridPaint)
            canvas.drawLine(gapRight - dp(3f), y, gapRight, y, gridPaint)
            label(canvas, "$db", (gapLeft + gapRight) / 2f, y + dp(3f), p.dim, Paint.Align.CENTER)
        }
    }

    /** dB scale in the right hand gutter, used when there is no middle gap to put it in. */
    private fun drawScaleOnRight(canvas: Canvas, right: Float, top: Float, bottom: Float) {
        val p = palette
        applyGridColors()
        for (db in scaleSteps()) {
            val y = dbToY(10f.pow(db / 20f), top, bottom)
            canvas.drawLine(right - dp(21f), y, right - dp(17f), y, gridPaint)
            label(canvas, "$db", right, y + dp(3f), p.dim, Paint.Align.RIGHT)
        }
    }

    // ------------------------------------------------------------------ horizontal layout

    private fun drawHorizontal(canvas: Canvas, w: Float, h: Float, levels: com.litescope.dsp.LevelFrame) {
        val p = palette
        val pad = dp(5f)
        val list = channels()
        val panel = panelLayout(dp(150f))
        // Condensed: a narrower text block, tighter gaps and slimmer pills than before.
        val panelWidth = if (panel.rows == 0) 0f else min(w * 0.34f, dp(124f))
        val pillWidth = if (w > dp(300f)) dp(48f) else dp(40f)
        val gap = dp(4f)
        val labelMode = prefs.meterLabelPosition
        // UNDER keeps a pill column on the left.
        val leftColumn = if (labelMode == LABEL_UNDER) pillWidth else 0f
        val clipGutter = dp(9f)
        val pillsLeft = pad + (if (panelWidth > 0f) panelWidth + gap else 0f)
        val barsLeft = pillsLeft + leftColumn + (if (leftColumn > 0f) gap else 0f)
        val barsRight = w - pad - clipGutter
        val showScale = prefs.meterShowScale && h > dp(88f)
        val scaleH = if (showScale) dp(12f) else 0f
        val availH = h - pad * 2 - scaleH
        val rowH = (availH / list.size).coerceAtLeast(dp(16f))
        val barHeight = min(rowH - dp(6f), dp(26f)).coerceAtLeast(dp(6f))

        if (panelWidth > 0f) {
            drawLoudness(canvas, pad, pad, pad + panelWidth, h - pad, panel, levels)
        }

        list.forEachIndexed { index, channel ->
            val rowTop = pad + index * rowH
            val y = rowTop + (rowH - barHeight) / 2f
            val accent = channel.accent(p)
            val peak = channel.peak()
            val rms = channel.rms()

            when (labelMode) {
                LABEL_UNDER -> drawValuePill(
                    canvas,
                    pillsLeft,
                    rowTop + (rowH - dp(22f)) / 2f,
                    pillsLeft + pillWidth,
                    rowTop + (rowH + dp(22f)) / 2f,
                    dbText(peak),
                    accent,
                    w > dp(260f)
                )
                else -> Unit // ON_BAR draws over the bar after the fill
            }

            trackPaint.color = if (p.isLight) 0x14000000 else 0x33000000
            rect.set(barsLeft, y, barsRight, y + barHeight)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), trackPaint)

            if (rms > 1e-5f) {
                barPaint.shader = meterGradientH(barsLeft, barsRight)
                rect.set(barsLeft, y, dbToX(rms, barsLeft, barsRight), y + barHeight)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), barPaint)
                barPaint.shader = null
            }

            val peakX = dbToX(peak, barsLeft, barsRight)
            linePaint.color = p.text
            linePaint.strokeWidth = dp(2f)
            canvas.drawLine(peakX, y, peakX, y + barHeight, linePaint)

            val holdX = dbToX(channel.hold(levels), barsLeft, barsRight)
            linePaint.color = p.alpha(p.warn, 0.95f)
            linePaint.strokeWidth = dp(1.5f)
            canvas.drawLine(holdX, y, holdX, y + barHeight, linePaint)

            label(
                canvas,
                channel.label,
                barsLeft + dp(3f),
                y + barHeight / 2f + dp(3f),
                if (p.isLight) 0xFF101418.toInt() else accent
            )
            if (labelMode == LABEL_ON_BAR) {
                val badgeW = min(dp(46f), (barsRight - barsLeft) / 2f)
                drawValueBadge(
                    canvas,
                    barsLeft + dp(20f),
                    y + barHeight / 2f - dp(9f),
                    barsLeft + dp(20f) + badgeW,
                    y + barHeight / 2f + dp(9f),
                    dbText(peak),
                    accent
                )
            }
            // Vertical clip indicator outside the meter, on the right.
            drawClipBar(
                canvas,
                barsRight + dp(5f),
                y,
                dp(4f),
                barHeight,
                levels.clippedAt
            )
        }


        if (showScale) {
            applyGridColors()
            val scaleY = h - pad + dp(1f)
            for (db in scaleSteps()) {
                val x = dbToX(10f.pow(db / 20f), barsLeft, barsRight)
                canvas.drawLine(x, scaleY - dp(3f), x, scaleY, gridPaint)
                label(canvas, "$db", x, scaleY - dp(5f), p.dim, Paint.Align.CENTER)
            }
        }
    }

    // ------------------------------------------------------------------ primitives

    /** Rounded value pill, the design's main numeric element. */
    private fun drawValuePill(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
        accent: Int,
        big: Boolean
    ) {
        if (right - left < dp(16f) || bottom - top < dp(12f)) return
        val p = palette
        val radius = dp(8f)
        boxRect.set(left, top, right, bottom)
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = if (p.isLight) 0x14000000 else 0x33FFFFFF
        canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = dp(1f)
        boxPaint.color = p.alpha(accent, 0.5f)
        canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        boxPaint.style = Paint.Style.FILL

        labelPaint.textSize = (if (big) 13f else 10f) * density
        label(
            canvas,
            text,
            (left + right) / 2f,
            (top + bottom) / 2f + labelPaint.textSize * 0.36f,
            p.text,
            Paint.Align.CENTER
        )
        labelPaint.textSize = 9f * density
    }

    /** Vertical clip indicator used by the horizontal layout (one per channel, outside the bar). */
    private fun drawClipBar(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        clippedAt: Long
    ) {
        val p = palette
        val age = SystemClock.elapsedRealtime() - clippedAt
        val lit = age < 2000L
        val fade = if (lit) (1f - age / 2000f).coerceIn(0.3f, 1f) else 0f
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = if (lit) p.alpha(p.bad, fade) else p.alpha(p.bad, 0.22f)
        boxRect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(boxRect, dp(2f), dp(2f), boxPaint)
    }

    /** Small translucent badge with a value, used for the on-bar / between / outside placements. */
    private fun drawValueBadge(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
        accent: Int
    ) {
        if (right - left < dp(14f) || bottom - top < dp(10f)) return
        val p = palette
        val radius = dp(6f)
        boxRect.set(left, top, right, bottom)
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = p.alpha(p.bg, if (p.isLight) 0.72f else 0.62f)
        canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = dp(1f)
        boxPaint.color = p.alpha(accent, 0.6f)
        canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        boxPaint.style = Paint.Style.FILL
        labelPaint.textSize = 10f * density
        label(
            canvas,
            text,
            (left + right) / 2f,
            (top + bottom) / 2f + labelPaint.textSize * 0.36f,
            p.text,
            Paint.Align.CENTER
        )
        labelPaint.textSize = 9f * density
    }

    /** Latching clip rectangle in the meters' own design language. */
    private fun drawClipRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, clippedAt: Long) {
        val p = palette
        val age = SystemClock.elapsedRealtime() - clippedAt
        val lit = age < 2000L
        val fade = if (lit) (1f - age / 2000f).coerceIn(0.3f, 1f) else 0f
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = if (lit) p.alpha(p.bad, fade) else p.alpha(p.bad, 0.22f)
        boxRect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(boxRect, dp(2f), dp(2f), boxPaint)
        boxPaint.style = Paint.Style.FILL
    }

    /** Dedicated loudness block: text values plus the correlation bar. */
    private fun drawLoudness(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        layout: PanelLayout,
        levels: com.litescope.dsp.LevelFrame
    ) {
        if (right - left < dp(52f) || bottom - top < dp(16f)) return
        val p = palette
        boxPaint.style = Paint.Style.FILL
        boxPaint.style = Paint.Style.FILL
        boxPaint.color = p.panel
        boxRect.set(left, top, right, bottom)
        canvas.drawRoundRect(boxRect, dp(7f), dp(7f), boxPaint)

        val rowH = dp(13f)
        val textLeft = left + dp(6f)
        val textRight = right - dp(6f)
        var y = top + rowH - dp(3f)
        // Header so the block is self-explanatory: everything below is level in dBFS.
        if (bottom - top > dp(52f)) {
            label(canvas, "LEVELS dBFS", textLeft, y, p.alpha(p.dim, 0.85f))
            y += dp(11f)
        }

        if (prefs.meterShowNumeric) {
            val peakDb = 20f * log10(max(max(levels.peakL, levels.peakR), 1e-6f))
            val rmsDb = 20f * log10(max(max(levels.rmsL, levels.rmsR), 1e-6f))
            val crest = peakDb - rmsDb
            val balance = 20f * log10(max(levels.rmsR, 1e-6f) / max(levels.rmsL, 1e-6f))
            if (layout.twoColumns) {
                label(canvas, "PEAK", textLeft, y, p.dim)
                label(canvas, dbText(peakDb), textLeft + dp(42f), y, p.text)
                label(canvas, "RMS", textRight - dp(78f), y, p.dim, Paint.Align.RIGHT)
                label(canvas, dbText(rmsDb), textRight, y, p.text, Paint.Align.RIGHT)
                y += rowH
                label(canvas, "CREST", textLeft, y, p.dim)
                label(
                    canvas,
                    String.format(java.util.Locale.US, "%.1f dB", crest),
                    textLeft + dp(42f),
                    y,
                    p.text
                )
                label(canvas, "BAL", textRight - dp(78f), y, p.dim, Paint.Align.RIGHT)
                label(
                    canvas,
                    String.format(java.util.Locale.US, "%+.1f dB", balance),
                    textRight,
                    y,
                    p.text,
                    Paint.Align.RIGHT
                )
                y += rowH
            } else {
                fun row(labelText: String, valueText: String) {
                    label(canvas, labelText, textLeft, y, p.dim)
                    label(canvas, valueText, textRight, y, p.text, Paint.Align.RIGHT)
                    y += rowH
                }
                row("PEAK", dbText(peakDb))
                row("RMS", dbText(rmsDb))
                row("CREST", String.format(java.util.Locale.US, "%.1f dB", crest))
                row("BAL", String.format(java.util.Locale.US, "%+.1f dB", balance))
            }
        }

        if (prefs.meterShowCorrelation) {
            val corrLabelY = y + dp(2f)
            val barTop = corrLabelY + dp(4f)
            val barBottom = barTop + dp(6f)
            trackPaint.color = if (p.isLight) 0x1A000000 else 0x33000000
            rect.set(textLeft, barTop, textRight, barBottom)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), trackPaint)
            val corr = levels.correlation.coerceIn(-1f, 1f)
            val mid = (textLeft + textRight) / 2f
            applyGridColors()
            canvas.drawLine(mid, barTop - dp(1f), mid, barBottom + dp(1f), gridMajorPaint)
            val x = textLeft + (corr + 1f) / 2f * (textRight - textLeft)
            boxPaint.color = when {
                corr < -0.2f -> p.bad
                corr < 0.5f -> p.warn
                else -> p.good
            }
            boxRect.set(x - dp(1.5f), barTop - dp(1.5f), x + dp(1.5f), barBottom + dp(1.5f))
            canvas.drawRoundRect(boxRect, dp(1.5f), dp(1.5f), boxPaint)
            label(
                canvas,
                String.format(java.util.Locale.US, "CORR %+.2f", corr),
                textLeft,
                corrLabelY,
                p.dim
            )
        }
    }

    // ------------------------------------------------------------------ layout helpers

    /** Panel shape for a given width: two columns when there is room, one when there is not. */
    private class PanelLayout(val rows: Int, val twoColumns: Boolean)

    private fun panelLayout(widthPx: Float): PanelLayout {
        val twoColumns = widthPx >= dp(215f)
        var rows = 0
        if (prefs.meterShowNumeric) rows += if (twoColumns) 2 else 4
        if (prefs.meterShowCorrelation) rows += 1
        return PanelLayout(rows, twoColumns)
    }

    private fun panelHeight(layout: PanelLayout): Float {
        if (layout.rows == 0) return 0f
        var h = dp(13f) * layout.rows + dp(9f)
        if (prefs.meterShowCorrelation) h += dp(8f)
        return h
    }

    /** dB values of the scale labels, denser near the top where it matters. */
    private fun scaleSteps(): IntArray = intArrayOf(0, -6, -12, -20, -30, -45, -60)

    private fun dbToY(amplitude: Float, top: Float, bottom: Float): Float =
        bottom - dbToFraction(amplitude) * (bottom - top)

    private fun dbToX(amplitude: Float, left: Float, right: Float): Float =
        left + dbToFraction(amplitude) * (right - left)

    private fun dbToFraction(amplitude: Float): Float {
        val db = 20f * log10(max(amplitude, 1e-6f))
        return ((db - MIN_DB) / (0f - MIN_DB)).coerceIn(0f, 1f)
    }

    private fun dbText(value: Float): String =
        if (value <= MIN_DB + 0.5f) "-∞" else String.format(java.util.Locale.US, "%.1f", value)

    private fun meterGradient(top: Float, bottom: Float): LinearGradient {
        val p = palette
        val key = "v${p.good}-${p.warn}-${p.bad}-${top.toInt()}-${bottom.toInt()}"
        gradient?.takeIf { gradientKey == key }?.let { return it }
        val shader = LinearGradient(
            0f, bottom, 0f, top,
            intArrayOf(p.good, p.good, p.warn, p.bad),
            floatArrayOf(0f, 0.55f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        gradient = shader
        gradientKey = key
        return shader
    }

    private fun meterGradientH(left: Float, right: Float): LinearGradient {
        val p = palette
        return LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(p.good, p.good, p.warn, p.bad),
            floatArrayOf(0f, 0.55f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    companion object {
        const val TOOL_ID = "meters"
        private const val MIN_DB = -60f

        /** Value text placements. */
        const val LABEL_UNDER = 0
        const val LABEL_ON_BAR = 1
    }
}

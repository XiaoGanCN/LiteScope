package com.litescope.audio

import android.os.SystemClock
import com.litescope.dsp.LevelFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Peak / RMS / correlation measurements taken from the raw capture blocks.
 *
 * Peak hold decays over the configured hold time and the clip indicator latches for two seconds
 * so short overloads stay visible.
 */
class LevelMeter {

    private var holdL = 0f
    private var holdR = 0f
    private var holdUntilL = 0L
    private var holdUntilR = 0L
    private var clippedAt = 0L
    private var seq = 0L

    fun process(buf: FloatArray, frames: Int, holdMs: Int): LevelFrame {
        var sumL = 0.0
        var sumR = 0.0
        var sumLR = 0.0
        var sumMid = 0.0
        var sumSide = 0.0
        var sumSum = 0.0
        var peakSum = 0f
        var peakL = 0f
        var peakR = 0f
        var i = 0
        for (f in 0 until frames) {
            val l = buf[i]
            val r = buf[i + 1]
            i += 2
            val al = abs(l)
            val ar = abs(r)
            if (al > peakL) peakL = al
            if (ar > peakR) peakR = ar
            sumL += (l * l).toDouble()
            sumR += (r * r).toDouble()
            sumLR += (l * r).toDouble()
            val m = (l + r) * 0.5f
            val sd = (l - r) * 0.5f
            sumMid += (m * m).toDouble()
            sumSide += (sd * sd).toDouble()
            val sm = l + r
            sumSum += (sm * sm).toDouble()
            val asm = abs(sm)
            if (asm > peakSum) peakSum = asm
        }
        if (peakL >= 0.999f || peakR >= 0.999f) clippedAt = SystemClock.elapsedRealtime()

        val rmsL = sqrt(sumL / max(1, frames)).toFloat()
        val rmsR = sqrt(sumR / max(1, frames)).toFloat()
        val midRms = sqrt(sumMid / max(1, frames)).toFloat()
        val sideRms = sqrt(sumSide / max(1, frames)).toFloat()
        val sumRms = sqrt(sumSum / max(1, frames)).toFloat()
        val denom = sqrt(sumL * sumR)
        val correlation = if (denom > 1e-12) (sumLR / denom).toFloat().coerceIn(-1f, 1f) else 1f

        val now = SystemClock.elapsedRealtime()
        if (peakL >= holdL || now > holdUntilL) {
            holdL = peakL
            holdUntilL = now + holdMs
        }
        if (peakR >= holdR || now > holdUntilR) {
            holdR = peakR
            holdUntilR = now + holdMs
        }

        return LevelFrame(
            peakL = peakL,
            peakR = peakR,
            rmsL = rmsL,
            rmsR = rmsR,
            holdL = holdL,
            holdR = holdR,
            correlation = correlation,
            midRms = midRms,
            sideRms = sideRms,
            sumPeak = peakSum,
            sumRms = sumRms,
            clippedAt = clippedAt,
            seq = seq++
        )
    }
}

package com.litescope.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Windowed-sinc FIR low pass followed by integer decimation.
 *
 * Used to derive the "analysis sample rate" of the spectrum scope from the capture rate
 * (48 kHz capture -> 16 kHz analysis with `factor == 3`) without aliasing the spectrum.
 * The filter keeps its delay line between calls, so block boundaries stay continuous.
 */
class FirDecimator(val factor: Int, taps: Int = 63) {

    private val h: FloatArray
    private val delay: FloatArray
    private var pos = 0
    private var counter = 0

    init {
        require(factor >= 1) { "decimation factor must be >= 1" }
        val n = if (taps % 2 == 0) taps + 1 else taps
        h = FloatArray(n)
        delay = FloatArray(n)
        val m = (n - 1) / 2
        // Cutoff just below the decimated Nyquist frequency (normalised: 1.0 == sample rate).
        val fc = 0.46f / factor
        var sum = 0f
        for (i in 0 until n) {
            val x = (i - m).toFloat()
            val sinc = if (x == 0f) 2f * fc else sin(2.0 * PI * fc * x).toFloat() / (PI.toFloat() * x)
            val w = 0.42f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat() +
                0.08f * cos(4.0 * PI * i / (n - 1)).toFloat()
            h[i] = sinc * w
            sum += h[i]
        }
        if (sum != 0f) {
            for (i in 0 until n) h[i] /= sum
        }
    }

    /** Number of delay-line taps (for latency reporting). */
    val tapCount: Int get() = h.size

    /** Group delay introduced by the filter, in input samples. */
    val groupDelay: Int get() = (h.size - 1) / 2

    /** Clears filter state (used when the capture format changes). */
    fun reset() {
        delay.fill(0f)
        pos = 0
        counter = 0
    }

    /**
     * Consumes [inputLen] samples of [input] and writes every `factor`-th filtered sample to
     * [out]. Returns the number of samples written, which is at most `inputLen / factor + 1`.
     */
    fun process(input: FloatArray, inputLen: Int, out: FloatArray): Int {
        var written = 0
        val n = h.size
        val cap = delay.size
        for (i in 0 until inputLen) {
            delay[pos] = input[i]
            pos++
            if (pos >= cap) pos = 0
            counter++
            if (counter >= factor) {
                counter = 0
                var acc = 0f
                var idx = pos - 1
                if (idx < 0) idx += cap
                for (k in 0 until n) {
                    acc += h[k] * delay[idx]
                    idx--
                    if (idx < 0) idx += cap
                }
                if (written < out.size) {
                    out[written] = acc
                    written++
                }
            }
        }
        return written
    }
}

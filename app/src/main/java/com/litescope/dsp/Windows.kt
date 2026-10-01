package com.litescope.dsp

import kotlin.math.PI
import kotlin.math.cos

/** Analysis windows offered by the spectrum scope. */
enum class WindowType(val label: String) {
    HANN("Hann"),
    HAMMING("Hamming"),
    BLACKMAN_HARRIS("Blackman-Harris"),
    FLAT_TOP("Flat top"),
    RECTANGULAR("Rect");

    companion object {
        fun fromName(name: String?, fallback: WindowType = HANN): WindowType =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}

object Windows {

    /** Normalised window coefficients (peak 1.0) of the requested [size]. */
    fun coefficients(type: WindowType, size: Int): FloatArray {
        val w = FloatArray(size)
        if (size <= 1) {
            w.fill(1f)
            return w
        }
        val denom = (size - 1).toDouble()
        when (type) {
            WindowType.RECTANGULAR -> w.fill(1f)
            WindowType.HANN -> for (i in 0 until size) {
                w[i] = (0.5 - 0.5 * cos(2.0 * PI * i / denom)).toFloat()
            }
            WindowType.HAMMING -> for (i in 0 until size) {
                w[i] = (0.54 - 0.46 * cos(2.0 * PI * i / denom)).toFloat()
            }
            WindowType.BLACKMAN_HARRIS -> for (i in 0 until size) {
                val x = 2.0 * PI * i / denom
                w[i] = (0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)).toFloat()
            }
            WindowType.FLAT_TOP -> for (i in 0 until size) {
                val x = 2.0 * PI * i / denom
                w[i] = (0.21557895 - 0.41663158 * cos(x) + 0.277263158 * cos(2 * x) -
                    0.083578947 * cos(3 * x) + 0.006947368 * cos(4 * x)).toFloat()
            }
        }
        return w
    }

    /**
     * Coherent gain (mean of the window). Dividing a windowed FFT magnitude by
     * `size/2 * coherentGain` yields the amplitude of a full-scale sinusoid, so 0 dBFS
     * corresponds to a sine of amplitude 1.0 regardless of the selected window.
     */
    fun coherentGain(type: WindowType): Float = when (type) {
        WindowType.RECTANGULAR -> 1.0f
        WindowType.HANN -> 0.5f
        WindowType.HAMMING -> 0.54f
        WindowType.BLACKMAN_HARRIS -> 0.35875f
        WindowType.FLAT_TOP -> 0.21557895f
    }
}

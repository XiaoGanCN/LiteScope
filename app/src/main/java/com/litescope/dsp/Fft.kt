package com.litescope.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place iterative radix-2 complex FFT with precomputed twiddles and a bit-reversal table.
 *
 * The transform used throughout LiteScope is the forward DFT
 * `X[k] = sum_n x[n] * exp(-j*2*pi*k*n/N)` on real input (imaginary part zeroed by the caller).
 */
class Fft(val size: Int) {

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val bitReverse = IntArray(size)

    init {
        for (i in 0 until size / 2) {
            val angle = 2.0 * PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        var j = 0
        for (i in 0 until size) {
            bitReverse[i] = j
            var m = size shr 1
            while (m != 0 && (j and m) != 0) {
                j = j xor m
                m = m shr 1
            }
            j = j or m
        }
    }

    /** Result bin count for real input (`size / 2`, covering DC..Nyquist at `size/2 + 1` points). */
    val bins: Int get() = size / 2

    /** Runs the transform in place. `re` and `im` must be at least [size] long. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = size
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var j = i
                val end = i + half
                while (j < end) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val l = j + half
                    val tre = re[l] * c + im[l] * s
                    val tim = im[l] * c - re[l] * s
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                    j++
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Writes `sqrt(re^2 + im^2)` for bins `0 until bins` into [out]. */
    fun magnitudes(re: FloatArray, im: FloatArray, out: FloatArray) {
        for (k in 0 until bins) {
            val r = re[k]
            val i = im[k]
            out[k] = sqrt(r * r + i * i)
        }
    }
}

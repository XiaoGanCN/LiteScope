package com.litescope.dsp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Frequency -> musical note conversion for the spectrum readout. */
object Notes {

    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * e.g. `A4 +3c` or `F#3 -12c`; empty for frequencies outside the audible range.
     *
     * @param referenceHz frequency of A4, 440 Hz for standard concert pitch
     */
    fun describe(hz: Float, referenceHz: Float = 440f): String {
        if (hz < 15f || hz > 21000f) return ""
        val reference = if (referenceHz > 1f) referenceHz.toDouble() else 440.0
        val midi = 69.0 + 12.0 * (ln(hz / reference) / ln(2.0))
        val nearest = midi.roundToInt()
        val cents = ((midi - nearest) * 100.0).roundToInt()
        val name = NAMES[((nearest % 12) + 12) % 12]
        val octave = nearest / 12 - 1
        val sign = if (cents >= 0) "+" else "-"
        return String.format(Locale.US, "%s%d %s%dc", name, octave, sign, abs(cents))
    }

    /** Linear amplitude (0..1) to dBFS. */
    fun toDb(amplitude: Float): Float =
        if (amplitude <= 1e-7f) -140f else (20.0 * kotlin.math.log10(amplitude.toDouble())).toFloat()

    /** dBFS to a linear amplitude. */
    fun fromDb(db: Float): Float = 10f.pow(db / 20f)
}

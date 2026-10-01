package com.litescope.dsp

/**
 * One analysis frame of the spectrum scope.
 *
 * @param db magnitude per bin in dBFS (0 dBFS == full scale sine), length == [bins]
 * @param sampleRate analysis sample rate in Hz (after decimation)
 * @param binHz frequency width of one bin
 * @param peakHz interpolated frequency of the strongest bin above the noise floor
 * @param peakDb level of that bin in dBFS
 */
class SpectrumFrame(
    val db: FloatArray,
    val bins: Int,
    val sampleRate: Int,
    val binHz: Float,
    val peakHz: Float,
    val peakDb: Float,
    val seq: Long
)

/**
 * Stereo level / phase measurements derived from the raw (non decimated) capture stream.
 *
 * @param peakL peak sample magnitude of the last block, 0..1+
 * @param rmsL RMS magnitude of the last block, 0..1
 * @param holdL peak-hold value maintained by the meter
 * @param correlation inter-channel correlation, -1..1
 * @param midRms RMS of (L+R)/2
 * @param sideRms RMS of (L-R)/2
 * @param clippedAt last time a sample reached full scale, `SystemClock.elapsedRealtime()`
 */
class LevelFrame(
    val peakL: Float,
    val peakR: Float,
    val rmsL: Float,
    val rmsR: Float,
    val holdL: Float,
    val holdR: Float,
    val correlation: Float,
    val midRms: Float,
    val sideRms: Float,
    /** Peak of the literal L + R sum, used by the meters' combined stereo mode. */
    val sumPeak: Float,
    /** RMS of the literal L + R sum. */
    val sumRms: Float,
    val clippedAt: Long,
    val seq: Long
)

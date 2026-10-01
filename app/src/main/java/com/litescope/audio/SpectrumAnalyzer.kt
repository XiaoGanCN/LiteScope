package com.litescope.audio

import com.litescope.core.ScopeHub
import com.litescope.dsp.Fft
import com.litescope.dsp.FirDecimator
import com.litescope.dsp.SpectrumFrame
import com.litescope.dsp.WindowType
import com.litescope.dsp.Windows
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the raw capture stream into spectrum frames.
 *
 * Pipeline per block: read `hopIn` stereo frames -> measure levels -> down-mix to the selected
 * channel -> optional FIR decimation to the analysis sample rate -> overlapping windows through a
 * FIFO -> windowed FFT -> dBFS conversion with tilt and averaging -> publish.
 *
 * The frame rate is bounded twice: by the requested overlap and by [MAX_FRAMES_PER_SECOND], so a
 * small FFT cannot burn the CPU on a fast device.
 */
class SpectrumAnalyzer(private val hub: ScopeHub) : Thread("LiteScope-FFT") {

    @Volatile
    private var running = true

    private val levelMeter = LevelMeter()

    private var cfgGeneration = -1
    private var cfgCaptureRate = 0

    private var fftSize = 2048
    private var fft = Fft(2048)
    private var window = FloatArray(2048)
    private var normFactor = 1024f
    private var re = FloatArray(2048)
    private var im = FloatArray(2048)
    private var mag = FloatArray(1024)
    private var averaged = FloatArray(1024)
    private var firstFrame = true

    private var div = 1
    private var decimator: FirDecimator? = null
    private var analysisRate = 48000
    private var hopIn = 1024
    private var hopOut = 1024

    private var block = FloatArray(4096)
    private var mono = FloatArray(4096)
    private var decimated = FloatArray(4096)
    private var fifo = FloatArray(8192)
    private var fifoLen = 0

    private var cursor = -1L
    private var seq = 0L

    fun shutdown() {
        running = false
        interrupt()
        hub.bus.stop()
    }

    override fun run() {
        while (running) {
            val bus = hub.bus
            val prefs = hub.prefs
            if (bus.capacity == 0 || bus.sampleRate <= 0) {
                sleepQuietly(50)
                continue
            }
            if (prefs.generation != cfgGeneration || bus.sampleRate != cfgCaptureRate) {
                reconfigure()
            }
            if (cursor < 0 || cursor < bus.oldestFrame) cursor = bus.oldestFrame
            if (!bus.awaitFrames(cursor, hopIn, WAIT_MS)) {
                if (!running) break
                if (bus.stopped) sleepQuietly(80)
                continue
            }
            val got = bus.copyStereo(block, cursor, hopIn)
            if (got <= 0) {
                sleepQuietly(10)
                continue
            }
            cursor += got
            hub.publishLevels(levelMeter.process(block, got, prefs.meterHoldMs))

            val monoCount = downmix(block, got, prefs.spectrumChannel, mono)
            val decCount = if (decimator != null) {
                decimator!!.process(mono, monoCount, decimated)
            } else {
                System.arraycopy(mono, 0, decimated, 0, monoCount)
                monoCount
            }
            appendFifo(decimated, decCount)
            while (fifoLen >= fftSize) {
                publishFrame(prefs.spectrumAveraging, prefs.spectrumTilt, prefs.tiltDbPerOctave)
                val drop = min(hopOut, fifoLen)
                System.arraycopy(fifo, drop, fifo, 0, fifoLen - drop)
                fifoLen -= drop
            }
        }
    }

    private fun reconfigure() {
        val prefs = hub.prefs
        val bus = hub.bus
        cfgGeneration = prefs.generation
        cfgCaptureRate = bus.sampleRate

        fftSize = prefs.fftSize
        fft = Fft(fftSize)
        val windowType = WindowType.fromName(prefs.windowType)
        window = Windows.coefficients(windowType, fftSize)
        normFactor = (fftSize / 2f) * Windows.coherentGain(windowType)
        re = FloatArray(fftSize)
        im = FloatArray(fftSize)
        mag = FloatArray(fftSize / 2)
        averaged = FloatArray(fftSize / 2)
        firstFrame = true

        val captureRate = bus.sampleRate.coerceAtLeast(8000)
        var d = prefs.analysisDivisor.coerceAtLeast(1)
        if (captureRate % d != 0) d = 1
        if (d != div) decimator = null
        div = d
        if (d > 1 && decimator == null) decimator = FirDecimator(d)
        analysisRate = captureRate / d

        val overlapHop = when (prefs.fftOverlap) {
            0 -> fftSize
            1 -> fftSize / 2
            else -> fftSize / 4
        }.coerceAtLeast(1)
        val minHop = (analysisRate / MAX_FRAMES_PER_SECOND).coerceAtLeast(1)
        var outHop = max(overlapHop, minHop)
        var inHop = ((outHop + d - 1) / d) * d

        // Never ask for more frames than half the ring holds.
        val ringCap = if (bus.capacity > 0) bus.capacity else fftSize * 2 * d
        val maxIn = (ringCap / 2).coerceAtLeast(d)
        if (inHop > maxIn) {
            inHop = (maxIn / d) * d
            if (inHop < d) inHop = d
            outHop = inHop / d
        }
        hopIn = inHop
        hopOut = max(1, outHop)

        block = FloatArray(hopIn * 2)
        mono = FloatArray(hopIn)
        decimated = FloatArray(hopIn / div + 2)
        fifo = FloatArray(fftSize + hopOut + 8)
        fifoLen = 0
        cursor = bus.oldestFrame
    }

    private fun downmix(buf: FloatArray, frames: Int, mode: Int, out: FloatArray): Int {
        var i = 0
        when (mode) {
            1 -> for (f in 0 until frames) {
                out[f] = buf[i]
                i += 2
            }
            2 -> for (f in 0 until frames) {
                out[f] = buf[i + 1]
                i += 2
            }
            else -> for (f in 0 until frames) {
                out[f] = (buf[i] + buf[i + 1]) * 0.5f
                i += 2
            }
        }
        return frames
    }

    private fun appendFifo(src: FloatArray, count: Int) {
        if (count <= 0) return
        var n = count
        val space = fifo.size - fifoLen
        if (n > space) n = space
        if (n <= 0) return
        System.arraycopy(src, 0, fifo, fifoLen, n)
        fifoLen += n
    }

    private fun publishFrame(averaging: Float, tilt: Boolean, tiltSlope: Float) {
        for (i in 0 until fftSize) {
            re[i] = fifo[i] * window[i]
        }
        java.util.Arrays.fill(im, 0f)
        fft.transform(re, im)
        fft.magnitudes(re, im, mag)

        val bins = fftSize / 2
        val binHz = analysisRate.toFloat() / fftSize
        val s = averaging.coerceIn(0f, 0.95f)
        var peakDb = -200f
        var peakBin = 0
        for (k in 0 until bins) {
            val amp = mag[k] / normFactor
            var db = 20f * log10(max(amp, 1e-9f))
            if (tilt) {
                val f = max(k * binHz, 20f)
                db += tiltSlope * log2(f / 1000f)
            }
            val v = if (firstFrame) db else averaged[k] * s + db * (1f - s)
            averaged[k] = v
            if (v > peakDb) {
                peakDb = v
                peakBin = k
            }
        }
        firstFrame = false

        var peakHz = peakBin * binHz
        if (peakBin in 1 until bins - 1) {
            val a = mag[peakBin - 1]
            val b = mag[peakBin]
            val c = mag[peakBin + 1]
            val denom = a - 2f * b + c
            if (denom != 0f) {
                val delta = 0.5f * (a - c) / denom
                if (delta > -1f && delta < 1f) peakHz = (peakBin + delta) * binHz
            }
        }

        hub.publishSpectrum(
            SpectrumFrame(
                db = averaged.copyOf(bins),
                bins = bins,
                sampleRate = analysisRate,
                binHz = binHz,
                peakHz = peakHz,
                peakDb = peakDb,
                seq = seq++
            )
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val WAIT_MS = 200L
        private const val MAX_FRAMES_PER_SECOND = 60
    }
}

package com.litescope.audio

import com.litescope.core.AudioBus
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in signal generator.
 *
 * It writes PCM straight into the [AudioBus], which makes the whole analysis chain testable without
 * any app playing audio: useful to check the scopes, to calibrate levels (the 1 kHz tone is exactly
 * full scale) and to compare left/right alignment (the dual tone). Channel routing is switchable so
 * the vector scope and the correlation meter have something meaningful to show.
 */
class SignalGenerator(private val bus: AudioBus) {

    enum class Signal(val label: String) {
        SINE_1K("1 kHz tone"),
        SINE_SWEEP("20 Hz – 20 kHz sweep"),
        DUAL_TONE("440 Hz L / 660 Hz R"),
        PINK_NOISE("Pink noise"),
        WHITE_NOISE("White noise")
    }

    /** -1 = both, 0 = left only, 1 = right only. */
    @Volatile
    var channelRouting: Int = -1

    /** Peak amplitude, 0..1. */
    @Volatile
    var amplitude: Float = 0.5f

    /** 0 = silent, 1 = generator output. Blended with the capture stream. */
    @Volatile
    var mix: Float = 1f

    @Volatile
    var signal: Signal = Signal.SINE_1K

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var phaseL = 0.0
    private var phaseR = 0.0
    private var sweepPhase = 0.0
    private val pink = FloatArray(7)
    private val random = Random(0x5EED)

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "LiteScope-SignalGen")
        t.priority = Thread.NORM_PRIORITY
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        thread?.let {
            try {
                it.join(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    private fun loop() {
        val chunk = 512
        var rate = bus.sampleRate.coerceAtLeast(8000)
        val buffer = FloatArray(chunk * 2)
        var sweepPos = 0.0
        while (running) {
            rate = bus.sampleRate.coerceAtLeast(8000)
            val amp = amplitude.coerceIn(0f, 1f)
            for (i in 0 until chunk) {
                val l: Float
                val r: Float
                when (signal) {
                    Signal.SINE_1K -> {
                        phaseL += 2.0 * PI * 1000.0 / rate
                        l = (sin(phaseL) * amp).toFloat()
                        r = l
                    }
                    Signal.SINE_SWEEP -> {
                        // 20 Hz -> 20 kHz over 8 seconds, then wrap.
                        val t = sweepPos / (rate * 8.0)
                        val f = 20.0 * Math.pow(1000.0, t)
                        sweepPhase += 2.0 * PI * f / rate
                        l = (sin(sweepPhase) * amp).toFloat()
                        r = l
                        sweepPos += 1.0
                        if (sweepPos >= rate * 8.0) {
                            sweepPos = 0.0
                            sweepPhase = 0.0
                        }
                    }
                    Signal.DUAL_TONE -> {
                        phaseL += 2.0 * PI * 440.0 / rate
                        phaseR += 2.0 * PI * 660.0 / rate
                        l = (sin(phaseL) * amp).toFloat()
                        r = (sin(phaseR) * amp).toFloat()
                    }
                    Signal.PINK_NOISE -> {
                        val white = random.nextFloat() * 2f - 1f
                        // Paul Kellet's economy pink noise filter.
                        pink[0] = 0.99886f * pink[0] + white * 0.0555179f
                        pink[1] = 0.99332f * pink[1] + white * 0.0750759f
                        pink[2] = 0.96900f * pink[2] + white * 0.1538520f
                        pink[3] = 0.86650f * pink[3] + white * 0.3104856f
                        pink[4] = 0.55000f * pink[4] + white * 0.5329522f
                        pink[5] = -0.7616f * pink[5] - white * 0.0168980f
                        val out = (pink[0] + pink[1] + pink[2] + pink[3] + pink[4] + pink[5] +
                            pink[6] + white * 0.5362f) * 0.11f
                        pink[6] = white * 0.115926f
                        l = (out * amp * 3f).coerceIn(-1f, 1f)
                        r = l
                    }
                    Signal.WHITE_NOISE -> {
                        l = ((random.nextFloat() * 2f - 1f) * amp).coerceIn(-1f, 1f)
                        r = ((random.nextFloat() * 2f - 1f) * amp).coerceIn(-1f, 1f)
                    }
                }
                val m = mix.coerceIn(0f, 1f)
                val left = when (channelRouting) {
                    0 -> l * m
                    1 -> 0f
                    else -> l * m
                }
                val right = when (channelRouting) {
                    0 -> 0f
                    1 -> r * m
                    else -> r * m
                }
                buffer[i * 2] = left
                buffer[i * 2 + 1] = right
            }
            bus.write(buffer, chunk, 2)
            try {
                // Keep roughly one chunk of latency.
                Thread.sleep((chunk * 1000L / rate).coerceAtLeast(1L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    companion object {
        /** Peak frequency of the sweep at normalised position [t] (0..1) — used by the UI hint. */
        fun sweepFrequency(t: Float): Float = (20.0 * Math.pow(1000.0, t.toDouble().coerceIn(0.0, 1.0))).toFloat()

        @Suppress("unused")
        private fun unusedMathHelpers(a: Float): Float = max(min(ln(a.toDouble()).toFloat(), 1f), exp(1f))
    }
}

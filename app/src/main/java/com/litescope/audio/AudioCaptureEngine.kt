package com.litescope.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import androidx.core.content.ContextCompat
import com.litescope.core.AudioBus
import kotlin.math.max

/**
 * Captures the audio the device is *playing* (not the microphone) through
 * `AudioPlaybackCaptureConfiguration` + `AudioRecord`.
 *
 * The requested sample rate is attempted first, then the device's native output rate, then the two
 * common rates; the first combination that initialises wins and the effective rate is reported
 * back so the frequency axis stays accurate. The capture runs on its own thread and pushes into
 * [AudioBus]; extra consumers (the WAV recorder) receive the same blocks through [Listener].
 */
class AudioCaptureEngine(
    private val context: Context,
    private val bus: AudioBus
) {

    interface Listener {
        /** Called once, before the first block, with the effective capture format. */
        fun onCaptureConfigured(sampleRate: Int, channels: Int)

        /** Raw interleaved float samples, called from the capture thread. */
        fun onCaptureSamples(buf: FloatArray, frames: Int, channels: Int)

        /** Called from the capture thread when the loop exits. */
        fun onCaptureStopped(reason: String)
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Effective capture rate in Hz, 0 until [start] succeeds. */
    @Volatile
    var sampleRate: Int = 0
        private set

    @Volatile
    var channels: Int = 2
        private set

    /** Encoding actually negotiated with the platform. */
    @Volatile
    var encoding: Int = AudioFormat.ENCODING_PCM_16BIT
        private set

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Builds and starts the record. [requestedRate] of 0 means "use the device default".
     * Returns true when audio is flowing.
     */
    fun start(projection: MediaProjection, requestedRate: Int): Boolean {
        if (isRunning) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; playback capture unavailable")
            return false
        }
        val built = buildRecord(projection, requestedRate) ?: return false
        record = built.record
        sampleRate = built.sampleRate
        channels = built.channels
        encoding = built.encoding
        bus.configure(sampleRate, HISTORY_SECONDS)
        listener?.onCaptureConfigured(sampleRate, channels)
        try {
            built.record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            built.record.release()
            record = null
            return false
        }
        isRunning = true
        val t = Thread({ readLoop(built) }, "LiteScope-Capture")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
        return true
    }

    fun stop(reason: String = "stopped") {
        val wasRunning = isRunning
        isRunning = false
        listener?.let { if (wasRunning) it.onCaptureStopped(reason) }
        try {
            record?.stop()
        } catch (t: Throwable) {
            // already stopped
        }
        thread?.let {
            try {
                it.join(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        try {
            record?.release()
        } catch (t: Throwable) {
            // ignore
        }
        record = null
    }

    private class Built(
        val record: AudioRecord,
        val sampleRate: Int,
        val channels: Int,
        val encoding: Int
    )

    private fun buildRecord(projection: MediaProjection, requestedRate: Int): Built? {
        // Lint needs the runtime check close to the AudioRecord construction as well.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "playback capture configuration rejected", t)
            return null
        }
        val encodings = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        for (rate in candidateRates(requestedRate)) {
            for (enc in encodings) {
                val channelMask = AudioFormat.CHANNEL_IN_STEREO
                val minBuf = try {
                    AudioRecord.getMinBufferSize(rate, channelMask, enc)
                } catch (t: Throwable) {
                    AudioRecord.ERROR
                }
                if (minBuf <= 0) continue
                val bufferBytes = max(minBuf * 2, rate * 2 * 2 / 5)
                val format = AudioFormat.Builder()
                    .setEncoding(enc)
                    .setSampleRate(rate)
                    .setChannelMask(channelMask)
                    .build()
                var candidate: AudioRecord? = null
                try {
                    candidate = AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord($rate, enc=$enc) failed: ${t.message}")
                }
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    val actualRate = if (candidate.sampleRate > 0) candidate.sampleRate else rate
                    val actualChannels = if (candidate.channelCount > 0) candidate.channelCount else 2
                    Log.i(TAG, "Playback capture: $actualRate Hz, $actualChannels ch, enc=$enc")
                    return Built(candidate, actualRate, actualChannels, enc)
                }
                try {
                    candidate?.release()
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
        Log.e(TAG, "no usable playback capture format (requested rate=$requestedRate)")
        return null
    }

    private fun candidateRates(requested: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        if (requested > 0) out.add(requested)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val native = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (native != null && native > 0) out.add(native)
        } catch (t: Throwable) {
            // ignore
        }
        out.add(48000)
        out.add(44100)
        return out.toList()
    }

    private fun readLoop(built: Built) {
        val rec = built.record
        val ch = built.channels.coerceAtLeast(1)
        val chunkFrames = 2048
        val chunkSamples = chunkFrames * ch
        var reason = "capture loop ended"
        try {
            if (built.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val buf = FloatArray(chunkSamples)
                while (isRunning) {
                    val read = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val frames = read / ch
                        bus.write(buf, frames, ch)
                        listener?.onCaptureSamples(buf, frames, ch)
                    } else if (read < 0) {
                        reason = "capture read error $read"
                        break
                    }
                }
            } else {
                val shortBuf = ShortArray(chunkSamples)
                val floatBuf = FloatArray(chunkSamples)
                while (isRunning) {
                    val read = rec.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        for (i in 0 until read) floatBuf[i] = shortBuf[i] / 32768f
                        val frames = read / ch
                        bus.write(floatBuf, frames, ch)
                        listener?.onCaptureSamples(floatBuf, frames, ch)
                    } else if (read < 0) {
                        reason = "capture read error $read"
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            reason = "capture failed: ${t.message}"
            Log.e(TAG, "read loop crashed", t)
        } finally {
            val wasRunning = isRunning
            isRunning = false
            bus.stop()
            if (wasRunning) listener?.onCaptureStopped(reason)
        }
    }

    companion object {
        private const val TAG = "LiteScope/Capture"
        private const val HISTORY_SECONDS = 6f
    }
}

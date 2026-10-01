package com.litescope.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams the captured playback audio to a 16 bit PCM WAV file.
 *
 * Blocks are handed over through a bounded queue and written by a dedicated thread, so the audio
 * capture thread never blocks on storage (dropped blocks are counted instead). On API 29+ the file
 * lands in `Music/LiteScope` through MediaStore and needs no storage permission; if MediaStore is
 * unavailable the app-private recordings folder is used instead.
 */
class WavRecorder(private val context: Context) {

    private class Sink(
        val uri: Uri?,
        val pfd: ParcelFileDescriptor,
        val channel: FileChannel,
        val path: String?
    )

    private val queue = ArrayBlockingQueue<ByteArray>(64)

    @Volatile
    private var writer: Thread? = null

    @Volatile
    private var sink: Sink? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var recordedFrames: Long = 0L
        private set

    @Volatile
    var droppedBlocks: Long = 0L
        private set

    @Volatile
    var outputUri: Uri? = null
        private set

    @Volatile
    var outputPath: String? = null
        private set

    @Volatile
    var sampleRate: Int = 48000
        private set

    @Volatile
    var channels: Int = 2
        private set

    private var scratch = ShortArray(0)

    val recordedSeconds: Float
        get() = if (sampleRate <= 0) 0f else recordedFrames.toFloat() / sampleRate

    /** Opens the output file and starts the writer thread. Returns false when it cannot record. */
    fun start(sampleRate: Int, channels: Int): Boolean {
        if (isRecording) return true
        this.sampleRate = sampleRate
        this.channels = channels.coerceIn(1, 2)
        recordedFrames = 0
        droppedBlocks = 0
        queue.clear()
        val opened = openSink() ?: return false
        sink = opened
        outputUri = opened.uri
        outputPath = opened.path ?: opened.uri?.toString()
        isRecording = true
        val t = Thread({ writerLoop(opened) }, "LiteScope-WAV")
        t.priority = Thread.NORM_PRIORITY
        writer = t
        t.start()
        return true
    }

    /** Called from the capture thread; converts float frames to little endian PCM16 blocks. */
    fun write(interleaved: FloatArray, frames: Int, channels: Int) {
        if (!isRecording || frames <= 0) return
        val samples = frames * channels
        if (scratch.size < samples) scratch = ShortArray(samples)
        val bytes = ByteArray(samples * 2)
        var bi = 0
        for (i in 0 until samples) {
            val v = (interleaved[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[bi++] = (v and 0xFF).toByte()
            bytes[bi++] = ((v shr 8) and 0xFF).toByte()
        }
        if (!queue.offer(bytes)) droppedBlocks++
        recordedFrames += frames
    }

    /** Flushes, patches the WAV header and closes the file. Returns the finished media Uri. */
    fun stop(): Uri? {
        if (!isRecording) return null
        isRecording = false
        val t = writer
        writer = null
        try {
            t?.join(3000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val s = sink
        sink = null
        val uri = s?.uri
        if (s != null) {
            try {
                val dataBytes = recordedFrames * channels * 2
                s.channel.position(0)
                writeHeader(s.channel, dataBytes, channels, sampleRate)
                s.channel.close()
            } catch (t2: Throwable) {
                // ignore, file may be partially written
            }
            try {
                s.pfd.close()
            } catch (t2: Throwable) {
                // ignore
            }
            if (s.uri != null) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(s.uri, values, null, null)
                } catch (t2: Throwable) {
                    // ignore
                }
            }
        }
        return uri
    }

    private fun writerLoop(s: Sink) {
        try {
            while (isRecording || queue.isNotEmpty()) {
                val block = try {
                    queue.poll(120, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }
                if (block != null) {
                    try {
                        writeFully(s.channel, block)
                    } catch (t: Throwable) {
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            // Keep the capture pipeline alive even if storage fails.
        }
    }

    private fun openSink(): Sink? {
        val name = "LiteScope_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        // Preferred: shared Music collection (no permission needed on API 29+).
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LiteScope")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                if (pfd != null) {
                    val channel = FileOutputStream(pfd.fileDescriptor).channel
                    writeHeader(channel, 0, channels, sampleRate)
                    return Sink(uri, pfd, channel, null)
                }
            }
        } catch (t: Throwable) {
            // fall through to the app-private location
        }
        // Fallback: app specific external storage.
        return try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, name)
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
            )
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            writeHeader(channel, 0, channels, sampleRate)
            Sink(null, pfd, channel, file.absolutePath)
        } catch (t: Throwable) {
            null
        }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun writeHeader(channel: FileChannel, dataBytes: Long, channels: Int, sampleRate: Int) {
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        var i = 0
        fun ascii(s: String) {
            for (c in s) header[i++] = c.code.toByte()
        }
        fun le32(v: Long) {
            header[i++] = (v and 0xFF).toByte()
            header[i++] = ((v shr 8) and 0xFF).toByte()
            header[i++] = ((v shr 16) and 0xFF).toByte()
            header[i++] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(v: Int) {
            header[i++] = (v and 0xFF).toByte()
            header[i++] = ((v shr 8) and 0xFF).toByte()
        }
        ascii("RIFF")
        le32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1) // PCM
        le16(channels)
        le32(sampleRate.toLong())
        le32(byteRate.toLong())
        le16(channels * 2)
        le16(16)
        ascii("data")
        le32(dataBytes)
        writeFully(channel, header)
    }
}

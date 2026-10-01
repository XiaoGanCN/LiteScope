package com.litescope.service

import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.litescope.audio.AudioCaptureEngine
import com.litescope.audio.SignalGenerator
import com.litescope.core.CaptureStatus
import com.litescope.core.Prefs
import com.litescope.core.ScopeHub
import com.litescope.core.Tool
import com.litescope.overlay.OverlayManager

/**
 * Foreground service that owns the whole inspection pipeline: MediaProjection based playback
 * capture, the analysis thread, the floating overlay windows and the WAV recorder.
 *
 * On Android 14+ the service must enter the foreground with the `mediaProjection` type *before*
 * `getMediaProjection()` is called, which is exactly the order used in [startCapture].
 */
class ScopeService : Service(), AudioCaptureEngine.Listener, Prefs.Listener {

    inner class LocalBinder : Binder() {
        val service: ScopeService get() = this@ScopeService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    lateinit var hub: ScopeHub
        private set

    lateinit var overlays: OverlayManager
        private set

    private var engine: AudioCaptureEngine? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capturing = false
    private var foregroundStarted = false

    private val recordTicker = object : Runnable {
        override fun run() {
            if (hub.recorder.isRecording) {
                // The Chronometer shows the time; this only refreshes the recording state.
                refreshNotification()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        hub = ScopeHub.get(this)
        overlays = OverlayManager(this, hub)
        overlays.onVisibilityChanged = { refreshNotification() }
        overlays.attach()
        hub.prefs.addListener(this)
        NotificationFactory.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture("stopped by user")
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_TOOL -> {
                val tool = Tool.fromId(intent.getStringExtra(EXTRA_TOOL))
                if (tool != null) {
                    overlays.toggle(tool)
                }
                safeForeground()
                refreshNotification()
                return START_NOT_STICKY
            }
            ACTION_SET_TOOL -> {
                val tool = Tool.fromId(intent.getStringExtra(EXTRA_TOOL))
                if (tool != null) {
                    overlays.setVisible(tool, intent.getBooleanExtra(EXTRA_VISIBLE, true))
                }
                safeForeground()
                refreshNotification()
                return START_NOT_STICKY
            }
            ACTION_SET_ALL_TOOLS -> {
                val visible = intent.getBooleanExtra(EXTRA_VISIBLE, true)
                for (tool in Tool.entries) overlays.setVisible(tool, visible)
                safeForeground()
                refreshNotification()
                return START_NOT_STICKY
            }
            ACTION_RESET_LAYOUT -> {
                overlays.resetLayout()
                safeForeground()
                refreshNotification()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECORD -> {
                toggleRecording()
                safeForeground()
                refreshNotification()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    startForegroundBestEffort()
                    setStatus("Missing screen capture token — reopen LiteScope")
                    refreshNotification()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, data)
                return START_NOT_STICKY
            }
            else -> {
                safeForeground()
                refreshNotification()
                return START_NOT_STICKY
            }
        }
    }

    // -------------------------------------------------------------- capture

    private fun startCapture(resultCode: Int, data: Intent) {
        if (capturing) return
        // Android 14+: the foreground service must already be running with the
        // mediaProjection type before the projection is created.
        startForegroundBestEffort()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val created = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            null
        }
        if (created == null) {
            setStatus("Screen capture permission was refused")
            refreshNotification()
            overlays.syncVisibility()
            return
        }
        projection = created
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // The projection was revoked (user stopped sharing, or the system did): tear the
                // service down, because a new capture needs a fresh consent token anyway.
                handler.post {
                    stopCapture("screen capture ended")
                    refreshNotification()
                    overlays.release()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        projectionCallback = callback
        try {
            created.registerCallback(callback, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "registerCallback failed", t)
        }

        hub.startAnalysis()
        val captureEngine = AudioCaptureEngine(this, hub.bus)
        captureEngine.setListener(this)
        engine = captureEngine
        capturing = captureEngine.start(created, hub.prefs.captureRate)
        if (!capturing) {
            setStatus("Playback capture unavailable — check the microphone permission")
        }
        if (hub.prefs.testSignal) {
            // The user prefers the built-in generator: drop the capture stream, keep the projection.
            setTestSignal(true)
        }
        overlays.syncVisibility()
        refreshNotification()
    }

    private fun stopCapture(reason: String) {
        capturing = false
        hub.generator.stop()
        engine?.stop(reason)
        engine = null
        if (hub.recorder.isRecording) {
            val uri = hub.recorder.stop()
            if (uri != null) toast("Recording saved: $uri")
        }
        handler.removeCallbacks(recordTicker)
        hub.prefs.testSignal = false
        val callback = projectionCallback
        if (callback != null) {
            try {
                projection?.unregisterCallback(callback)
            } catch (t: Throwable) {
                // ignore
            }
        }
        projectionCallback = null
        try {
            projection?.stop()
        } catch (t: Throwable) {
            // ignore
        }
        projection = null
        hub.stopAnalysis()
        hub.status.set(CaptureStatus(active = false, message = "Idle ($reason)"))
    }

    private fun shutdown() {
        overlays.release()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        hub.prefs.removeListener(this)
        stopCapture("service destroyed")
        overlays.release()
        super.onDestroy()
    }

    // -------------------------------------------------------------- recording

    fun toggleRecording(): Boolean {
        return if (hub.recorder.isRecording) {
            val uri = hub.recorder.stop()
            handler.removeCallbacks(recordTicker)
            toast(if (uri != null) "Saved recording: $uri" else "Recording failed")
            refreshNotification()
            false
        } else {
            val rate = if (hub.bus.sampleRate > 0) hub.bus.sampleRate else 48000
            val ok = hub.recorder.start(rate, 2)
            if (ok) {
                handler.postDelayed(recordTicker, 1000L)
                toast("Recording to Music/LiteScope")
            } else {
                toast("Could not start recording")
            }
            refreshNotification()
            ok
        }
    }

    // -------------------------------------------------------------- capture callbacks

    override fun onCaptureConfigured(sampleRate: Int, channels: Int) {
        hub.onCaptureFormatChanged(sampleRate)
        hub.status.set(
            CaptureStatus(
                active = true,
                sampleRate = sampleRate,
                channels = channels,
                analysisRate = hub.prefs.analysisRate(sampleRate),
                projectionGranted = true,
                testSignal = false,
                message = "Capturing"
            )
        )
        handler.post { refreshNotification() }
    }

    override fun onCaptureSamples(buf: FloatArray, frames: Int, channels: Int) {
        if (hub.recorder.isRecording) {
            hub.recorder.write(buf, frames, channels)
        }
    }

    override fun onCaptureStopped(reason: String) {
        hub.status.set(CaptureStatus(active = false, message = reason))
        handler.post { refreshNotification() }
    }

    // -------------------------------------------------------------- notification

    private fun startForegroundBestEffort() {
        if (foregroundStarted) return
        val notification = NotificationFactory.build(
            context = this,
            status = hub.status.get(),
            visible = { overlays.isVisible(it) || hub.prefs.toolVisible(it) },
            recording = hub.recorder.isRecording,
            recordedSeconds = hub.recorder.recordedSeconds
        )
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            ServiceCompat.startForeground(this, NotificationFactory.NOTIFICATION_ID, notification, type)
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun safeForeground() {
        if (foregroundStarted) return
        // Only allowed once a projection exists; otherwise leave the service in the background
        // (the notification chips are only reachable while it is already in the foreground).
        if (projection != null) startForegroundBestEffort()
    }

    private fun stopForegroundCompat() {
        if (!foregroundStarted) return
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // ignore
        }
        foregroundStarted = false
    }

    fun refreshNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationFactory.build(
            context = this,
            status = hub.status.get(),
            visible = { overlays.isVisible(it) || hub.prefs.toolVisible(it) },
            recording = hub.recorder.isRecording,
            recordedSeconds = hub.recorder.recordedSeconds
        )
        try {
            manager.notify(NotificationFactory.NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    private fun setStatus(message: String) {
        val current = hub.status.get()
        hub.status.set(
            CaptureStatus(
                active = current.active,
                sampleRate = current.sampleRate,
                channels = current.channels,
                analysisRate = current.analysisRate,
                projectionGranted = current.projectionGranted,
                testSignal = current.testSignal,
                message = message
            )
        )
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // ignore
        }
    }

    // -------------------------------------------------------------- built-in signal generator

    /**
     * Switches the analysis input between the playback capture stream and the built-in generator.
     * The generator exists so the scopes can be checked (and levels calibrated) without any app
     * playing audio. Returns the resulting state.
     */
    fun setTestSignal(enabled: Boolean): Boolean {
        val generator = hub.generator
        if (enabled) {
            engine?.stop("test signal enabled")
            engine = null
            capturing = false
            hub.onCaptureFormatChanged(GENERATOR_RATE)
            hub.startAnalysis()
            applyGeneratorSettings()
            generator.start()
            hub.status.set(
                CaptureStatus(
                    active = false,
                    sampleRate = GENERATOR_RATE,
                    channels = 2,
                    analysisRate = hub.prefs.analysisRate(GENERATOR_RATE),
                    testSignal = true,
                    message = "Test signal: " + generator.signal.label
                )
            )
            hub.prefs.testSignal = true
        } else {
            generator.stop()
            hub.prefs.testSignal = false
            restartCapture()
            hub.status.set(
                CaptureStatus(
                    active = capturing,
                    sampleRate = hub.bus.sampleRate,
                    channels = 2,
                    analysisRate = hub.prefs.analysisRate(hub.bus.sampleRate),
                    message = if (capturing) "Capturing" else "Idle"
                )
            )
        }
        overlays.syncVisibility()
        refreshNotification()
        return enabled
    }

    fun isTestSignalRunning(): Boolean = hub.generator.isRunning

    private fun applyGeneratorSettings() {
        val signals = SignalGenerator.Signal.entries
        hub.generator.signal = signals.getOrElse(hub.prefs.testSignalType) { SignalGenerator.Signal.SINE_1K }
        hub.generator.amplitude = hub.prefs.testSignalLevel
        hub.generator.mix = 1f
    }

    private fun restartCapture() {
        val proj = projection ?: return
        if (engine != null || capturing) return
        val captureEngine = AudioCaptureEngine(this, hub.bus)
        captureEngine.setListener(this)
        engine = captureEngine
        capturing = captureEngine.start(proj, hub.prefs.captureRate)
    }

    override fun onPrefsChanged(keys: Set<String>) {
        if (hub.generator.isRunning &&
            (keys.contains("test_signal_type") || keys.contains("test_signal_level"))
        ) {
            applyGeneratorSettings()
        }
        if (keys.contains("capture_rate")) {
            // The record has to be rebuilt for a new sample rate; everything else applies live.
            restartCaptureForRateChange()
        }
    }

    /** Rebuilds the AudioRecord when the requested capture rate changes, keeping the projection. */
    private fun restartCaptureForRateChange() {
        val active = projection ?: return
        if (hub.generator.isRunning) return
        capturing = false
        engine?.stop("sample rate change")
        engine = null
        setStatus("Restarting capture at ${hub.prefs.captureRate / 1000f} kHz…")
        refreshNotification()
        handler.postDelayed({
            val proj = projection ?: return@postDelayed
            val captureEngine = AudioCaptureEngine(this, hub.bus)
            captureEngine.setListener(this)
            engine = captureEngine
            capturing = captureEngine.start(proj, hub.prefs.captureRate)
            if (!capturing) {
                setStatus("Playback capture unavailable — check the microphone permission")
            }
            overlays.syncVisibility()
            refreshNotification()
        }, 300L)
    }

    // -------------------------------------------------------------- public API for the activity

    fun isCapturing(): Boolean = capturing

    fun captureStatus(): CaptureStatus = hub.status.get()

    fun stopEverything() {
        stopCapture("stopped by user")
        overlays.release()
        stopForegroundCompat()
        stopSelf()
    }

    companion object {
        private const val TAG = "LiteScope/Service"

        /** Sample rate used by the built-in generator when no capture format is known yet. */
        const val GENERATOR_RATE = 48000

        const val ACTION_START = "com.litescope.action.START"
        const val ACTION_STOP = "com.litescope.action.STOP"
        const val ACTION_TOGGLE_TOOL = "com.litescope.action.TOGGLE_TOOL"
        const val ACTION_SET_TOOL = "com.litescope.action.SET_TOOL"
        const val ACTION_SET_ALL_TOOLS = "com.litescope.action.SET_ALL_TOOLS"
        const val ACTION_RESET_LAYOUT = "com.litescope.action.RESET_LAYOUT"
        const val ACTION_TOGGLE_RECORD = "com.litescope.action.TOGGLE_RECORD"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_VISIBLE = "visible"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Starts capture with a fresh MediaProjection consent token. */
        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScopeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScopeService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // service not running
            }
        }

        /** Shows or hides every tool window in one go (used by the Quick Settings tile). */
        fun setAllTools(context: Context, visible: Boolean) {
            val intent = Intent(context, ScopeService::class.java)
                .setAction(ACTION_SET_ALL_TOOLS)
                .putExtra(EXTRA_VISIBLE, visible)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // service not running: nothing to sync
            }
        }

        fun setToolVisible(context: Context, tool: Tool, visible: Boolean) {
            val intent = Intent(context, ScopeService::class.java)
                .setAction(ACTION_SET_TOOL)
                .putExtra(EXTRA_TOOL, tool.id)
                .putExtra(EXTRA_VISIBLE, visible)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // service not running: the pref change alone is enough
            }
        }
    }
}

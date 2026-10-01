package com.litescope.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.litescope.R
import com.litescope.core.Prefs
import com.litescope.core.ScopeHub
import com.litescope.core.Tool
import com.litescope.service.ScopeService
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.litescope.view.MeterView
import com.litescope.view.Palette
import com.litescope.view.ScopeTheme
import com.litescope.view.SpectrumView
import com.litescope.view.VectorScopeView
import com.litescope.view.WaterfallView
import com.litescope.view.WaveformView

/**
 * Setup and configuration screen: permissions, capture control, per-tool toggles (the same ones the
 * notification exposes), a live in-app preview of the enabled tools and the full settings tree.
 *
 * The activity binds to [ScopeService] so it shares the same [ScopeHub] as the overlay windows.
 */
class MainActivity : AppCompatActivity(), SettingsScreen.Actions, Prefs.Listener {

    private val prefs: Prefs by lazy { Prefs.get(this) }
    private val hub: ScopeHub by lazy { ScopeHub.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var service: ScopeService? = null
    private var bound = false

    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var permissionCard: LinearLayout
    private lateinit var toolsCard: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var previewLabel: TextView
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var testSwitch: MaterialSwitch
    private var suppressTestSwitch = false

    private val previewViews = LinkedHashMap<Tool, View>()
    // Resolved lazily: a field initialiser would run before the activity context is attached.
    private var paletteCache: Palette? = null

    private val palette: Palette
        get() = paletteCache ?: ScopeTheme.palette(prefs).also { paletteCache = it }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? ScopeService.LocalBinder)?.service
            bound = service != null
            // Restore the floating windows the user had enabled before the process last stopped.
            service?.overlays?.syncVisibility()
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    /** Accent palette index -> theme overlay resource, applied before any view is inflated. */
    private fun accentThemeRes(index: Int): Int = when (index.coerceIn(0, 7)) {
        0 -> R.style.Theme_LiteScope_Teal
        1 -> R.style.Theme_LiteScope_Amber
        2 -> R.style.Theme_LiteScope_Violet
        3 -> R.style.Theme_LiteScope_Ice
        4 -> R.style.Theme_LiteScope_Mono
        5 -> R.style.Theme_LiteScope_Rose
        6 -> R.style.Theme_LiteScope_Lime
        else -> R.style.Theme_LiteScope_Ember
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Applied *after* super.onCreate: AppCompat re-applies the DayNight theme during its own
        // onCreate and would otherwise discard this accent overlay.
        setTheme(accentThemeRes(prefs.accentTheme))
        settingsScreen = SettingsScreen(this, prefs, this)
        setContentView(buildContent())
        settingsScreen.attach()
    }

    override fun onStart() {
        super.onStart()
        try {
            bindService(Intent(this, ScopeService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            bound = false
        }
        prefs.addListener(this)
        handler.post(ticker)
        rebuildPreview()
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        refreshStatus()
        rebuildTools()
        rebuildPermissions()
    }

    /** Re-applies the accent/background theme to the parts of the page built in code. */
    private fun applyTheme() {
        paletteCache = ScopeTheme.palette(prefs)
        findViewById<View>(android.R.id.content)?.setBackgroundColor(palette.bg)
    }

    override fun onPrefsChanged(keys: Set<String>) {
        if (!keys.contains("accent_theme") && !keys.contains("scope_bg")) return
        // Material widgets read their colours from the theme, which cannot change in place, so the
        // activity is recreated with the new accent/background theme overlay.
        if (keys.contains("scope_bg")) {
            AppCompatDelegate.setDefaultNightMode(
                if (ScopeTheme.isLightBackground(prefs.scopeBackground)) {
                    AppCompatDelegate.MODE_NIGHT_NO
                } else {
                    AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
        recreate()
    }

    override fun onStop() {
        prefs.removeListener(this)
        handler.removeCallbacks(ticker)
        if (bound) {
            try {
                unbindService(connection)
            } catch (t: Throwable) {
                // ignore
            }
            bound = false
            service = null
        }
        clearPreview()
        super.onStop()
    }

    override fun onDestroy() {
        settingsScreen.detach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildContent(): View {
        paletteCache = ScopeTheme.palette(prefs)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(palette.bg)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.bg))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(14), dp(12), dp(6))
        scroll.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = getString(R.string.app_name)
        title.setTextColor(palette.text)
        title.textSize = 21f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mode = TextView(this)
        mode.text = "playback audio inspector"
        mode.setTextColor(palette.dim)
        mode.textSize = 11f
        header.addView(mode)
        root.addView(header)

        // Status card
        val statusCard = card(root)
        statusTitle = TextView(this)
        statusTitle.setTextAppearance(R.style.RowTitle)
        statusCard.addView(statusTitle)
        statusDetail = TextView(this)
        statusDetail.setTextAppearance(R.style.RowSummary)
        statusCard.addView(statusDetail)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val startButton = MaterialButton(this)
        startButton.text = "Start capture"
        startButton.isAllCaps = false
        startButton.setOnClickListener { beginCaptureFlow() }
        buttons.addView(startButton, gridParams(0, end = 4))
        val stopButton = MaterialButton(this)
        stopButton.text = "Stop"
        stopButton.isAllCaps = false
        stopButton.setOnClickListener {
            service?.stopEverything() ?: ScopeService.stopService(this)
            handler.postDelayed({ refreshStatus() }, 300L)
        }
        buttons.addView(stopButton, gridParams(0, start = 4))
        statusCard.addView(buttons)

        val extra = LinearLayout(this)
        extra.orientation = LinearLayout.HORIZONTAL
        val recordButton = MaterialButton(this)
        recordButton.text = "Record WAV"
        recordButton.isAllCaps = false
        recordButton.setOnClickListener { toggleRecording() }
        extra.addView(recordButton, gridParams(8, end = 4))
        val layoutButton = MaterialButton(this)
        layoutButton.text = "Reset windows"
        layoutButton.isAllCaps = false
        layoutButton.setOnClickListener { resetWindowLayout() }
        extra.addView(layoutButton, gridParams(8, start = 4))
        statusCard.addView(extra)

        val testRow = LinearLayout(this)
        testRow.orientation = LinearLayout.HORIZONTAL
        testRow.gravity = Gravity.CENTER_VERTICAL
        val testText = TextView(this)
        testText.setTextAppearance(R.style.RowSummary)
        testText.text = "Test signal generator (works without any playback)"
        testRow.addView(testText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        testSwitch = MaterialSwitch(this)
        testSwitch.isChecked = prefs.testSignal
        testSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressTestSwitch) setTestSignal(checked)
        }
        testRow.addView(testSwitch)
        statusCard.addView(testRow)

        // Permissions + tools
        permissionCard = card(root)
        toolsCard = card(root)

        // Preview
        previewLabel = TextView(this)
        previewLabel.setTextAppearance(R.style.SectionHeader)
        previewLabel.text = "LIVE PREVIEW"
        root.addView(previewLabel)
        previewContainer = LinearLayout(this)
        previewContainer.orientation = LinearLayout.VERTICAL
        root.addView(previewContainer)

        // Settings
        root.addView(settingsScreen.build())

        val about = TextView(this)
        about.setTextAppearance(R.style.RowSummary)
        Ui.styleSummary(about, palette)
        about.setPadding(0, dp(18), 0, dp(24))
        about.text = "LiteScope captures playback audio through AudioPlaybackCapture (Android 10+). " +
            "Apps can opt out of playback capture, and DRM protected audio is never captured. " +
            "Scopes are drawn as \"display over other apps\" windows and can be toggled from the notification."
        root.addView(about)
        return scroll
    }

    /** Weighted button slot with the gutters the two button rows need. */
    private fun gridParams(top: Int = 0, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(top)
            marginStart = dp(start)
            marginEnd = dp(end)
        }

    private fun card(parent: LinearLayout): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.card(this, palette)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(12)
        parent.addView(card, params)
        return card
    }

    // ------------------------------------------------------------------ status

    private fun refreshStatus() {
        val status = service?.captureStatus() ?: hub.status.get()
        val active = status.active || service?.isCapturing() == true
        statusTitle.text = if (active) "Capturing playback audio" else "Not capturing"
        statusTitle.setTextColor(if (active) palette.accent else palette.warn)
        Ui.styleSummary(statusDetail, palette)
        statusDetail.text = buildString {
            append(status.describe())
            if (prefs.analysisDivisor > 1) {
                append("   ·   analysis ÷${prefs.analysisDivisor}")
            }
            append("\nFFT ${prefs.fftSize} · ")
            append(
                when (prefs.windowType) {
                    "HAMMING" -> "Hamming"
                    "BLACKMAN_HARRIS" -> "Blackman-Harris"
                    "FLAT_TOP" -> "Flat top"
                    "RECTANGULAR" -> "Rectangular"
                    else -> "Hann"
                }
            )
            append(" window · render ${prefs.renderFps} fps")
            append("\nService: ")
            append(if (bound) "connected" else "not running")
            append("  ·  Overlay: ")
            append(if (canDrawOverlays()) "allowed" else "blocked")
            if (hub.generator.isRunning) {
                append("\nTest signal generator active")
            }
            if (hub.recorder.isRecording) {
                append("\nRecording ")
                append(String.format(java.util.Locale.US, "%.1f s", hub.recorder.recordedSeconds))
            }
            if (!status.active && status.message.isNotEmpty()) {
                append("\n")
                append(status.message)
            }
        }
        val running = service?.isTestSignalRunning() ?: hub.generator.isRunning
        if (testSwitch.isChecked != running) {
            suppressTestSwitch = true
            testSwitch.isChecked = running
            suppressTestSwitch = false
        }
        previewLabel.visibility = if (prefs.previewInApp) View.VISIBLE else View.GONE
        previewContainer.visibility = if (prefs.previewInApp) View.VISIBLE else View.GONE
    }

    private fun rebuildPermissions() {
        permissionCard.removeAllViews()
        val header = TextView(this)
        header.setTextAppearance(R.style.SectionHeader)
        header.text = "PERMISSIONS"
        val headerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        permissionCard.addView(header)

        permissionRow(
            permissionCard,
            getString(R.string.perm_audio),
            hasMic(),
            "Grant"
        ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }

        permissionRow(
            permissionCard,
            getString(R.string.perm_overlay),
            canDrawOverlays(),
            "Grant"
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionRow(
                permissionCard,
                getString(R.string.perm_notif),
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                "Grant"
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF) }
        }
    }

    private fun permissionRow(
        parent: LinearLayout,
        title: String,
        granted: Boolean,
        actionLabel: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, 0)
        val text = TextView(this)
        text.setTextAppearance(R.style.RowTitle)
        text.text = (if (granted) "✓  " else "•  ") + title
        text.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.good else R.color.text)
        )
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!granted) {
            val button = MaterialButton(this)
            button.text = actionLabel
            Ui.styleButton(button, palette, this)
            button.setOnClickListener { action() }
            row.addView(button)
        }
        parent.addView(row)
    }

    private fun rebuildTools() {
        toolsCard.removeAllViews()
        val header = TextView(this)
        header.setTextAppearance(R.style.SectionHeader)
        header.text = "TOOLS (ALSO IN THE NOTIFICATION)"
        toolsCard.addView(header)

        for (tool in Tool.notificationOrder) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, 0)
            val column = LinearLayout(this)
            column.orientation = LinearLayout.VERTICAL
            val text = TextView(this)
            text.setTextAppearance(R.style.RowTitle)
            text.text = tool.title
            Ui.styleTitle(text, palette)
            val desc = TextView(this)
            desc.setTextAppearance(R.style.RowSummary)
            desc.text = toolDescription(tool)
            Ui.styleSummary(desc, palette)
            column.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(desc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val toggle = MaterialSwitch(this)
            toggle.isChecked = prefs.toolVisible(tool)
            Ui.styleSwitch(toggle, palette)
            toggle.setOnCheckedChangeListener { _, checked ->
                prefs.setToolVisible(tool, checked)
                rebuildPreview()
                if (checked && !canDrawOverlays()) {
                    toast("Grant \"display over other apps\" to float this tool")
                }
                if (checked && !hasMic()) {
                    toast("Microphone permission is required to capture playback")
                }
            }
            row.addView(toggle)
            toolsCard.addView(row)
        }
    }

    private fun toolDescription(tool: Tool): String = when (tool) {
        Tool.SPECTRUM -> "Real-time FFT with peak hold and pinch zoom"
        Tool.WATERFALL -> "Spectrogram history of the captured audio"
        Tool.WAVEFORM -> "Oscilloscope: overlay, split or summed stereo, trigger and zoom"
        Tool.VECTOR -> "Lissajous / goniometer stereo image"
        Tool.METERS -> "Peak, RMS, correlation and balance"
    }

    // ------------------------------------------------------------------ preview

    private fun rebuildPreview() {
        clearPreview()
        if (!prefs.previewInApp) return
        for (tool in Tool.notificationOrder) {
            if (!prefs.toolVisible(tool)) continue
            val view = createScopeView(tool)
            val wrapper = LinearLayout(this)
            wrapper.orientation = LinearLayout.VERTICAL
            wrapper.setPadding(dp(6), dp(6), dp(6), dp(6))
            wrapper.background = Ui.surface(this, palette.panel, palette.alpha(palette.accent, 0.18f), 14f)
            wrapper.clipToOutline = true
            val caption = TextView(this)
            caption.setTextAppearance(R.style.RowSummary)
            Ui.styleSummary(caption, palette)
            caption.text = tool.title
            wrapper.addView(caption)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (tool == Tool.VECTOR || tool == Tool.METERS) 220 else 170)
            )
            params.topMargin = dp(4)
            wrapper.addView(view, params)
            val wrapperParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            wrapperParams.topMargin = dp(8)
            previewContainer.addView(wrapper, wrapperParams)
            previewViews[tool] = view
        }
        if (previewViews.isEmpty()) {
            val hint = TextView(this)
            hint.setTextAppearance(R.style.RowSummary)
            hint.text = "Enable a tool above to preview it here."
            previewContainer.addView(hint)
        }
    }

    private fun clearPreview() {
        previewContainer.removeAllViews()
        previewViews.clear()
    }

    private fun createScopeView(tool: Tool): View = when (tool) {
        Tool.SPECTRUM -> SpectrumView(this)
        Tool.WATERFALL -> WaterfallView(this)
        Tool.WAVEFORM -> WaveformView(this)
        Tool.VECTOR -> VectorScopeView(this)
        Tool.METERS -> MeterView(this)
    }

    // ------------------------------------------------------------------ capture flow

    private fun beginCaptureFlow() {
        if (!hasMic()) {
            toast("Microphone permission is required to capture playback audio")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            toast("MediaProjection is not available on this device")
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            if (!anyToolVisible()) {
                prefs.setToolVisible(Tool.SPECTRUM, true)
                rebuildTools()
                rebuildPreview()
            }
            ScopeService.startWithProjection(this, resultCode, data)
            handler.postDelayed({ refreshStatus() }, 700L)
        } else {
            toast("Screen capture permission was denied — LiteScope needs it to read playback audio")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rebuildPermissions()
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginCaptureFlow()
        }
    }

    // ------------------------------------------------------------------ actions

    override fun effectiveCaptureRate(): Int {
        val live = service?.hub?.bus?.sampleRate ?: hub.bus.sampleRate
        return if (live > 0) live else hub.status.get().sampleRate
    }

    override fun isRecording(): Boolean = hub.recorder.isRecording

    override fun toggleRecording() {
        val svc = service
        if (svc == null) {
            toast("Start capture first")
            return
        }
        svc.toggleRecording()
        handler.postDelayed({ refreshStatus() }, 200L)
    }

    override fun setTestSignal(enabled: Boolean) {
        val svc = service
        if (svc != null) {
            svc.setTestSignal(enabled)
        } else {
            prefs.testSignal = enabled
            toast("Capture service is not running — open LiteScope and start capture first")
        }
        handler.postDelayed({ refreshStatus() }, 250L)
    }

    override fun isTestSignalRunning(): Boolean =
        service?.isTestSignalRunning() ?: hub.generator.isRunning

    override fun clearCursors() {
        hub.cursors.clear()
        toast("Measurement cursors cleared")
    }

    override fun resetWindowLayout() {
        service?.overlays?.resetLayout()
        toast("Window layout reset")
    }

    override fun resetAllSettings() {
        prefs.clearAll()
        settingsScreen.onPrefsChanged(emptySet())
        rebuildTools()
        rebuildPreview()
        toast("Settings reset")
    }

    // ------------------------------------------------------------------ helpers

    private fun anyToolVisible(): Boolean = Tool.entries.any { prefs.toolVisible(it) }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (t: Throwable) {
        false
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun View.buzz() {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PROJECTION = 1001
        private const val REQUEST_MIC = 1002
        private const val REQUEST_NOTIF = 1003
    }
}

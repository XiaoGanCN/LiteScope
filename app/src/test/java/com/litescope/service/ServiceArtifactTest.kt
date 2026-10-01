package com.litescope.service

import android.app.NotificationManager
import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.litescope.R
import com.litescope.core.CaptureStatus
import com.litescope.core.Prefs
import com.litescope.core.ScopeHub
import com.litescope.core.Tool
import com.litescope.core.WindowGeometry
import com.litescope.overlay.OverlayManager
import com.litescope.overlay.ToolWindow
import com.litescope.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Integration tests for the pieces the user actually touches: the notification toggle panel, the
 * settings tree and the floating window plumbing.
 *
 * The notification panel is inflated for real, so a stale view id or an unsupported RemoteViews
 * call fails the test rather than the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceArtifactTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val status = CaptureStatus(
        active = true,
        sampleRate = 48000,
        channels = 2,
        analysisRate = 48000,
        projectionGranted = true,
        message = "Capturing"
    )

    @Test
    fun notificationPanelInflatesAndReflectsTheEnabledTools() {
        NotificationFactory.createChannel(context)
        val parent = FrameLayout(context)

        val off = NotificationFactory.buildToolPanel(context, status, { false }, false, 0f)
        val offView = off.apply(context, parent)
        assertEquals(
            "Spectrum: off",
            offView.findViewById<android.view.View>(R.id.scope_spectrum).contentDescription.toString()
        )
        assertEquals(
            context.getString(R.string.notif_action_record),
            offView.findViewById<android.view.View>(R.id.action_record).contentDescription.toString()
        )
        // The timer row always shows something: the running time, or the idle state.
        val offTime = offView.findViewById<TextView>(R.id.notif_rec_time)
        assertEquals(android.view.View.VISIBLE, offTime.visibility)
        assertEquals("ready", offTime.text.toString())

        val on = NotificationFactory.buildToolPanel(context, status, { it == Tool.SPECTRUM }, true, 12.5f)
        val onView = on.apply(context, parent)
        assertEquals(
            "Spectrum: on",
            onView.findViewById<android.view.View>(R.id.scope_spectrum).contentDescription.toString()
        )
        assertEquals(
            "Waterfall: off",
            onView.findViewById<android.view.View>(R.id.scope_waterfall).contentDescription.toString()
        )
        assertEquals(
            context.getString(R.string.notif_action_stop_record),
            onView.findViewById<android.view.View>(R.id.action_record).contentDescription.toString()
        )
        // The timer is a self-ticking Chronometer now: the notification only tells it when to run,
        // so the text it renders is the platform's mm:ss rather than a re-posted string.
        val time = onView.findViewById<android.view.View>(R.id.notif_rec_time)
        assertEquals(android.view.View.VISIBLE, time.visibility)
        assertTrue(
            "expected a Chronometer, found ${time.javaClass.name}",
            time is android.widget.Chronometer
        )
    }

    @Test
    fun everyToolToggleAndActionExists() {
        val view = NotificationFactory.buildToolPanel(context, status, { true }, false, 0f)
            .apply(context, FrameLayout(context))
        for (id in intArrayOf(
            R.id.notif_status, R.id.notif_mini,
            R.id.scope_spectrum, R.id.scope_waterfall, R.id.scope_waveform,
            R.id.scope_vector, R.id.scope_meters,
            R.id.action_record, R.id.notif_rec_time,
            R.id.action_layout, R.id.action_settings, R.id.action_stop
        )) {
            assertNotNull("missing view $id", view.findViewById<android.view.View>(id))
        }
    }

    @Test
    fun collapsedPanelInflatesWithTheHeaderOnly() {
        val views = NotificationFactory.buildToolPanel(
            context, status, { true }, false, 0f,
            layoutRes = R.layout.notification_tools_compact
        )
        val view = views.apply(context, FrameLayout(context))
        assertNotNull("compact status missing", view.findViewById<android.view.View>(R.id.notif_status))
        assertNotNull("compact mini mark missing", view.findViewById<android.view.View>(R.id.notif_mini))
        // The toggles belong to the expanded panel, not the collapsed header.
        assertEquals(null, view.findViewById<android.view.View>(R.id.scope_spectrum))
    }

    @Test
    fun notificationGeometryMatchesTheDesign() {
        val prefs = Prefs.get(context)
        val views = NotificationFactory.buildToolPanel(context, status, { true }, false, 0f)
        val root = views.apply(context, FrameLayout(context))
        // 400 dp wide, which is what a phone gives the notification in Robolectric's 1:1 density.
        val width = 400
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, width, root.measuredHeight)

        // Every scope button must be a circle, never an oval stretched by the row layout.
        for (id in intArrayOf(
            R.id.scope_spectrum_bg, R.id.scope_waterfall_bg, R.id.scope_waveform_bg,
            R.id.scope_vector_bg, R.id.scope_meters_bg, R.id.action_record_bg
        )) {
            val view = root.findViewById<android.view.View>(id)
            assertNotNull("missing circle layer $id", view)
            assertEquals("circle $id is not square", view.width, view.height)
        }
        // ... and they must not touch each other: each circle sits in a wider weighted slot.
        for (id in intArrayOf(
            R.id.scope_spectrum_bg, R.id.scope_waterfall_bg, R.id.scope_waveform_bg,
            R.id.scope_vector_bg, R.id.scope_meters_bg
        )) {
            val circle = root.findViewById<android.view.View>(id)
            val slot = circle.parent as android.view.View
            val slack = slot.width - circle.width
            assertTrue("no padding around $id: slack=$slack", slack >= 4)
        }

        // The info pill must be a real, visible bar, not a zero-width strip.
        val pill = root.findViewById<android.view.View>(R.id.notif_info_bg)
        val outline = root.findViewById<android.view.View>(R.id.notif_info_outline)
        assertTrue("info pill has no width: ${pill.width}", pill.width > 100)
        assertTrue("info pill has no height: ${pill.height}", pill.height >= 20)
        assertEquals(pill.width, outline.width)

        // The card alone is wider than 2:1; together with the system header row the whole
        // notification block lands near the 2.4:1 of the sketch.
        val panelTop = root.findViewById<android.view.View>(R.id.notif_panel_bg)
        val ratio = panelTop.width.toFloat() / panelTop.height.toFloat()
        assertTrue("panel ratio $ratio is out of range", ratio > 2.0f && ratio < 4.2f)
    }

    @Test
    fun timerBaseIsDerivedFromElapsedSeconds() {
        // The factory hands the platform a base instant; the Chronometer derives mm:ss from it.
        assertEquals(5_000L, NotificationFactory.chronometerBase(0f, 5_000L))
        assertEquals(4_300L, NotificationFactory.chronometerBase(0.7f, 5_000L))
        assertEquals(-60_000L, NotificationFactory.chronometerBase(120f, 60_000L))
    }

    @Test
    fun notificationTitleReflectsTheCaptureFormat() {
        val view = NotificationFactory.buildToolPanel(context, status, { true }, false, 0f)
            .apply(context, FrameLayout(context))
        // The system header carries the app name; this content shows the capture format with its
        // miniature scope mark, exactly like the mock.
        val line = view.findViewById<TextView>(R.id.notif_status).text.toString()
        assertTrue(line, line.contains("48.0kHz Stereo"))
        assertNotNull(view.findViewById<android.view.View>(R.id.notif_mini))
    }

    @Test
    fun statusLineNamesTheActiveTools() {
        val line = NotificationFactory.statusLine(context, status, { true }, false, 0f)
        assertTrue(line, line.contains("Spectrum"))
        assertTrue(line, line.contains("Meters"))

        val none = NotificationFactory.statusLine(context, status, { false }, false, 0f)
        assertTrue(none, none.contains(context.getString(R.string.notif_text_none)))
        assertTrue(none, none.contains("48.0 kHz"))

        val recording = NotificationFactory.statusLine(context, status, { it == Tool.VECTOR }, true, 3.5f)
        assertTrue(recording, recording.contains("REC"))
        assertTrue(recording, recording.contains("Vector"))
    }

    @Test
    fun notificationBuildsForTheForegroundService() {
        val notification = NotificationFactory.build(context, status, { it == Tool.SPECTRUM }, false, 0f)
        assertNotNull(notification)
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(context.getSystemService(NotificationManager::class.java))
    }

    @Test
    fun settingsScreenBuildsEverySection() {
        val prefs = Prefs.get(context)
        // Material components need the app theme, and a plain application context does not have it.
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_LiteScope)
        val screen = SettingsScreen(themed, prefs, object : SettingsScreen.Actions {
            override fun effectiveCaptureRate(): Int = 48000
            override fun isRecording(): Boolean = false
            override fun toggleRecording() {}
            override fun setTestSignal(enabled: Boolean) {}
            override fun isTestSignalRunning(): Boolean = false
            override fun resetWindowLayout() {}
            override fun resetAllSettings() {}
            override fun clearCursors() {}
        })
        val root = screen.build() as LinearLayout
        // Capture + spectrum + waterfall + waveform + vector + meters + overlay sections, all rows.
        val views = countViews(root)
        assertTrue("settings screen looks empty: $views views", views > 150)
        // The capture section must expose both sample rates (requirement 5).
        assertTrue(hasText(root, "Capture sample rate"))
        assertTrue(hasText(root, "Spectrum analysis rate"))
        assertTrue(hasText(root, "History length"))
        assertTrue(hasText(root, "Time base"))
    }

    private fun countViews(view: android.view.View): Int {
        var count = 1
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) count += countViews(view.getChildAt(i))
        }
        return count
    }

    private fun hasText(view: android.view.View, needle: String): Boolean {
        if (view is TextView && view.text?.contains(needle) == true) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasText(view.getChildAt(i), needle)) return true
            }
        }
        return false
    }

    @Test
    fun themeIndicesSurviveARoundTrip() {
        val prefs = Prefs.get(context)
        for (accent in 0 until com.litescope.view.ScopeTheme.accentCount) {
            prefs.accentTheme = accent
            assertEquals("accent $accent was clamped on read", accent, prefs.accentTheme)
        }
        for (bg in 0 until com.litescope.view.ScopeTheme.backgroundCount) {
            prefs.scopeBackground = bg
            assertEquals("background $bg was clamped on read", bg, prefs.scopeBackground)
            assertEquals(
                "background $bg light flag",
                com.litescope.view.ScopeTheme.isLightBackground(bg),
                com.litescope.view.ScopeTheme.palette(prefs).isLight
            )
        }
        // The light treatments really must resolve to a light palette.
        prefs.scopeBackground = 7
        assertTrue(com.litescope.view.ScopeTheme.palette(prefs).isLight)
    }

    @Test
    fun mainActivityLaunchesAndBuildsItsUi() {
        val controller = org.robolectric.Robolectric.buildActivity(
            com.litescope.ui.MainActivity::class.java
        ).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertNotNull("activity produced no content view", activity.findViewById<android.view.View>(android.R.id.content))
        controller.pause().stop().destroy()
    }

    @Test
    fun toolWindowBuildsOverlayLayoutParams() {
        val prefs = Prefs.get(context)
        val window = ToolWindow(context, Tool.SPECTRUM, prefs, {}, {})
        val params = window.buildParams(WindowGeometry(12, 24, 320, 200), Point(1080, 2340))

        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertEquals(WindowGeometry(12, 24, 320, 200), window.geometry())

        // Locking a window makes it click-through.
        prefs.setToolLocked(Tool.SPECTRUM, true)
        window.applyPrefs()
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        prefs.setToolLocked(Tool.SPECTRUM, false)
        window.applyPrefs()
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
    }

    @Test
    fun overlayManagerShowsAndHidesWindows() {
        ShadowSettings.setCanDrawOverlays(true)
        val hub = ScopeHub.get(context)
        val manager = OverlayManager(context, hub)
        manager.attach()
        try {
            assertTrue(manager.canDrawOverlays())
            manager.syncVisibility()
            assertFalse(manager.isVisible(Tool.SPECTRUM))

            manager.setVisible(Tool.SPECTRUM, true)
            manager.setVisible(Tool.METERS, true)
            assertTrue(manager.isVisible(Tool.SPECTRUM))
            assertTrue(manager.isVisible(Tool.METERS))
            assertEquals(1, manager.otherRects(Tool.SPECTRUM).size)

            // Reset must keep both windows on screen.
            manager.resetLayout()
            assertTrue(manager.isVisible(Tool.SPECTRUM))

            manager.setVisible(Tool.SPECTRUM, false)
            assertFalse(manager.isVisible(Tool.SPECTRUM))
        } finally {
            manager.release()
        }
    }
}

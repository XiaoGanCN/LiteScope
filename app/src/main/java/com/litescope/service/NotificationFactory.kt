package com.litescope.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.litescope.R
import com.litescope.core.CaptureStatus
import com.litescope.core.Prefs
import com.litescope.core.Tool
import com.litescope.ui.MainActivity
import com.litescope.view.ScopeTheme

/**
 * Builds the ongoing notification, which doubles as the control panel.
 *
 * Layout: the five scope toggles sit on the first row as monochrome icons, the transport row holds
 * record (with the elapsed time next to it), reset layout, settings and stop. The status text is
 * merged into the second line of the header so the panel stays short.
 */
object NotificationFactory {

    /**
     * Notification palette. The panel follows the scope background, disabled buttons follow the
     * accent for their icon, and enabled buttons invert (accent fill, contrasting icon):
     *
     *  * OLED + Mono  -> black panel, deep grey buttons with white icons, enabled = white on black
     *  * OLED + Lime  -> black panel, deep grey buttons with lime icons, enabled = lime on black
     *  * Paper + Violet -> white panel, 95% white buttons with violet icons, enabled = violet on white
     */
    private class NotifColors(private val palette: com.litescope.view.Palette) {

        private val SHADE_SMALL = 18
        private val SHADE_LARGE = 34
        /** The card follows the scope background. */
        val panel = palette.bg

        /**
         * Disabled buttons are the panel colour nudged just enough to be visible: slightly lighter
         * on dark themes, slightly darker on light ones.
         */
        /**
         * Shades of the panel colour itself, so a coloured background (forest, plum, midnight)
         * keeps its hue instead of washing out to grey: dark themes get lifted, light ones sunk.
         */
        val disabledCircle = shade(panel, if (palette.isLight) -SHADE_SMALL else SHADE_SMALL)

        /** The info pill needs a bit more lift than the buttons to read as a container. */
        val infoPill = shade(panel, if (palette.isLight) -SHADE_LARGE else SHADE_LARGE)

        /** Accent outline of the info pill: guarantees it reads even on a low-contrast card. */
        val infoOutline = palette.alpha(palette.accent, 0.55f)

        /** Icons follow the accent, enabled buttons invert against the panel. */
        val disabledIcon = palette.accent
        val enabledCircle = palette.accent
        val enabledIcon = panel

        /** Record: disabled looks like any other disabled button, enabled goes red. */
        val recCircle = palette.bad
        val recDisabledCircle = disabledCircle
        val recDisabledIcon = disabledIcon
        val recEnabledIcon = panel
        val transport = palette.dim
        val status = palette.dim
        val timer = palette.text

        /** Adds [delta] to every channel, keeping the hue; negative values darken. */
        private fun shade(base: Int, delta: Int): Int {
            fun adjust(c: Int) = (c + delta).coerceIn(0, 255)
            return android.graphics.Color.argb(
                android.graphics.Color.alpha(base),
                adjust(android.graphics.Color.red(base)),
                adjust(android.graphics.Color.green(base)),
                adjust(android.graphics.Color.blue(base))
            )
        }
    }

    const val CHANNEL_ID = "litescope_capture"
    const val NOTIFICATION_ID = 4711

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        status: CaptureStatus,
        visible: (Tool) -> Boolean,
        recording: Boolean,
        recordedSeconds: Float
    ): Notification {
        val views = buildToolPanel(context, status, visible, recording, recordedSeconds)
        // The collapsed shade is only ~64dp tall, so it gets a shorter layout with just the toggles.
        val compact = buildToolPanel(
            context, status, visible, recording, recordedSeconds,
            layoutRes = R.layout.notification_tools_compact
        )
        val statusText = statusLine(context, status, visible, recording, recordedSeconds)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scope)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(settingsIntent(context))
            .setCustomContentView(compact)
            .setCustomBigContentView(views)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Short summary of the capture format, the enabled tools and the recording time. */
    fun statusLine(
        context: Context,
        status: CaptureStatus,
        visible: (Tool) -> Boolean,
        recording: Boolean,
        recordedSeconds: Float
    ): String {
        val active = Tool.notificationOrder.filter { visible(it) }
        return buildString {
            when {
                status.testSignal -> append("test signal")
                status.active -> {
                    append(String.format(java.util.Locale.US, "%.1f kHz", status.sampleRate / 1000f))
                    append(if (status.channels >= 2) " stereo" else " mono")
                }
                else -> append(status.message.ifEmpty { "idle" })
            }
            append("  ·  ")
            if (active.isEmpty()) {
                append(context.getString(R.string.notif_text_none))
            } else {
                append(active.joinToString(", ") { it.title })
            }
            if (recording) {
                append("  ·  REC ")
                append(String.format(java.util.Locale.US, "%.0fs", recordedSeconds))
            }
        }
    }

    /** Builds the control panel on its own so it can be exercised by tests. */
    fun buildToolPanel(
        context: Context,
        status: CaptureStatus,
        visible: (Tool) -> Boolean,
        recording: Boolean,
        recordedSeconds: Float,
        layoutRes: Int = R.layout.notification_tools
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        val palette = ScopeTheme.palette(Prefs.get(context))
        val compactLayout = layoutRes == R.layout.notification_tools_compact
        val colors = NotifColors(palette)
        val onColor = colors.enabledIcon
        val offColor = colors.disabledIcon

        // The card keeps its own rounded background (the mock is a black card), so it is not
        // recoloured here - setBackgroundColor would replace the rounded drawable.

        views.setInt(R.id.notif_mini, "setColorFilter", colors.status)
        if (!compactLayout) {
            views.setInt(R.id.notif_panel_bg, "setColorFilter", colors.panel)
            views.setInt(R.id.notif_info_bg, "setColorFilter", colors.infoPill)
            views.setInt(R.id.notif_info_outline, "setColorFilter", colors.infoOutline)
        }
        val headline = when {
            status.testSignal -> context.getString(R.string.notif_title_capturing, "test signal")
            status.active -> context.getString(R.string.notif_title_capturing, captureLabel(status))
            else -> status.message.ifEmpty { "Idle" }
        }
        views.setTextViewText(R.id.notif_status, headline)
        views.setTextColor(R.id.notif_status, colors.status)

        if (compactLayout) {
            // Collapsed shade shows the header only; the toggles live in the expanded panel.
            return views
        }

        for (tool in Tool.notificationOrder) {
            val id = chipId(tool)
            if (id == 0) continue
            val on = visible(tool)
            views.setInt(
                bgId(tool),
                "setColorFilter",
                if (on) colors.enabledCircle else colors.disabledCircle
            )
            views.setInt(id, "setColorFilter", if (on) onColor else offColor)
            views.setContentDescription(id, "${tool.title}: ${if (on) "on" else "off"}")
            views.setOnClickPendingIntent(id, toolIntent(context, tool))
        }

        if (compactLayout) return views

        views.setImageViewResource(
            R.id.action_record,
            if (recording) R.drawable.ic_stop else R.drawable.ic_record
        )
        views.setInt(
            R.id.action_record_bg,
            "setColorFilter",
            if (recording) colors.recCircle else colors.recDisabledCircle
        )
        views.setInt(
            R.id.action_record,
            "setColorFilter",
            if (recording) colors.recEnabledIcon else colors.recDisabledIcon
        )
        views.setContentDescription(
            R.id.action_record,
            context.getString(
                if (recording) R.string.notif_action_stop_record else R.string.notif_action_record
            )
        )
        // A Chronometer ticks itself, so the elapsed time stays accurate without the app re-posting
        // the notification ten times a second (which the system throttles after a few seconds and
        // which also made the action buttons flaky).
        views.setViewVisibility(R.id.notif_rec_time, View.VISIBLE)
        if (recording) {
            views.setChronometer(
                R.id.notif_rec_time,
                chronometerBase(recordedSeconds, android.os.SystemClock.elapsedRealtime()),
                null,
                true
            )
        } else {
            views.setChronometer(R.id.notif_rec_time, android.os.SystemClock.elapsedRealtime(), null, false)
            views.setTextViewText(R.id.notif_rec_time, idleLabel(context, status))
        }
        views.setTextColor(
            R.id.notif_rec_time,
            if (recording) palette.accent else palette.alpha(palette.dim, 0.8f)
        )

        // Transport icons are bare glyphs, as in the mock.
        views.setInt(R.id.action_layout, "setColorFilter", colors.transport)
        views.setInt(R.id.action_settings, "setColorFilter", colors.transport)
        views.setInt(R.id.action_stop, "setColorFilter", colors.transport)
        views.setTextColor(R.id.notif_rec_time, colors.timer)
        views.setOnClickPendingIntent(
            R.id.action_record,
            serviceIntent(context, ScopeService.ACTION_TOGGLE_RECORD, 300)
        )
        views.setOnClickPendingIntent(
            R.id.action_layout,
            serviceIntent(context, ScopeService.ACTION_RESET_LAYOUT, 301)
        )
        views.setOnClickPendingIntent(R.id.action_settings, settingsIntent(context))
        views.setOnClickPendingIntent(
            R.id.action_stop,
            serviceIntent(context, ScopeService.ACTION_STOP, 302)
        )
        return views
    }

    /** Slightly translucent panel fill so the glow behind the text reads on any background. */
    private fun panelColor(palette: com.litescope.view.Palette): Int =
        palette.alpha(palette.bg, 0.94f)

    /**
     * Base instant handed to the notification's Chronometer: the platform then keeps the elapsed
     * time running by itself, so the timer never stutters even though the app only re-posts the
     * notification once a second.
     */
    fun chronometerBase(recordedSeconds: Float, now: Long): Long =
        now - (recordedSeconds.coerceAtLeast(0f) * 1000f).toLong()

    private fun idleLabel(context: Context, status: CaptureStatus): String = when {
        status.testSignal -> "generator"
        status.active -> "ready"
        else -> "idle"
    }

    private fun captureLabel(status: CaptureStatus): String = String.format(
        java.util.Locale.US,
        "%.1fkHz %s",
        status.sampleRate / 1000f,
        if (status.channels >= 2) "Stereo" else "Mono"
    )

    /** Tintable circle layer that sits behind each tool icon. */
    private fun bgId(tool: Tool): Int = when (tool) {
        Tool.SPECTRUM -> R.id.scope_spectrum_bg
        Tool.WATERFALL -> R.id.scope_waterfall_bg
        Tool.WAVEFORM -> R.id.scope_waveform_bg
        Tool.VECTOR -> R.id.scope_vector_bg
        Tool.METERS -> R.id.scope_meters_bg
    }

    private fun chipId(tool: Tool): Int = when (tool) {
        Tool.SPECTRUM -> R.id.scope_spectrum
        Tool.WATERFALL -> R.id.scope_waterfall
        Tool.WAVEFORM -> R.id.scope_waveform
        Tool.VECTOR -> R.id.scope_vector
        Tool.METERS -> R.id.scope_meters
    }

    private fun toolIntent(context: Context, tool: Tool): PendingIntent {
        val intent = Intent(context, ScopeService::class.java)
            .setAction(ScopeService.ACTION_TOGGLE_TOOL)
            .putExtra(ScopeService.EXTRA_TOOL, tool.id)
        return PendingIntent.getService(
            context,
            100 + tool.ordinal,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun settingsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        303,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ScopeService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

package com.litescope.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.litescope.core.Prefs
import com.litescope.core.ScopeHub
import com.litescope.core.Tool
import com.litescope.core.WindowGeometry
import com.litescope.ui.MainActivity
import kotlin.math.max
import kotlin.math.min

/**
 * Owns every floating tool window: creation and removal from the notification/settings toggles,
 * magnetic snapping against the other windows, geometry persistence and live pref updates.
 */
class OverlayManager(private val context: Context, private val hub: ScopeHub) : Prefs.Listener {

    private val prefs: Prefs = hub.prefs
    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val windows = LinkedHashMap<Tool, ToolWindow>()
    private var quickSettings: QuickSettingsPanel? = null
    private val density = context.resources.displayMetrics.density

    /** Invoked whenever the set of visible tools changes, so the notification can refresh. */
    var onVisibilityChanged: (() -> Unit)? = null

    fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (t: Throwable) {
        false
    }

    /** Aligns the live windows with the persisted visibility flags. */
    fun syncVisibility() {
        if (!canDrawOverlays()) {
            for (tool in Tool.entries.toList()) remove(tool)
            return
        }
        for (tool in Tool.entries) {
            val shouldShow = prefs.toolVisible(tool)
            val shown = windows.containsKey(tool)
            if (shouldShow && !shown) add(tool)
            if (!shouldShow && shown) remove(tool)
        }
        applyPrefsToAll()
    }

    fun toggle(tool: Tool) {
        val visible = !windows.containsKey(tool)
        prefs.setToolVisible(tool, visible)
        if (visible) add(tool) else remove(tool)
        onVisibilityChanged?.invoke()
    }

    fun setVisible(tool: Tool, visible: Boolean) {
        prefs.setToolVisible(tool, visible)
        if (visible) add(tool) else remove(tool)
        onVisibilityChanged?.invoke()
    }

    fun isVisible(tool: Tool): Boolean = windows.containsKey(tool)

    private fun add(tool: Tool) {
        if (windows.containsKey(tool)) return
        val wm = windowManager ?: return
        if (!canDrawOverlays()) return
        val screen = screenSize()
        val window = ToolWindow(
            context = context,
            tool = tool,
            prefs = prefs,
            onClose = { t ->
                prefs.setToolVisible(t, false)
                remove(t)
                onVisibilityChanged?.invoke()
            },
            onOpenSettings = { openSettings() }
        )
        window.onOpenQuickSettings = { t -> openQuickSettings(t, window) }
        window.screenProvider = { screenSize() }
        window.otherRectsProvider = { otherRects(tool) }
        window.onGeometryCommitted = { t, geometry -> prefs.setGeometry(t, geometry) }
        window.windowManagerRef = wm
        val geometry = clampGeometry(prefs.geometry(tool), tool)
        val params = window.buildParams(geometry, screen)
        try {
            wm.addView(window.root, params)
        } catch (t: Throwable) {
            return
        }
        windows[tool] = window
        stagger(tool)
    }

    private fun remove(tool: Tool) {
        val window = windows.remove(tool) ?: return
        window.releasePopups()
        try {
            windowManager?.removeViewImmediate(window.root)
        } catch (t: Throwable) {
            // already detached
        }
    }

    /** Nudges a newly shown window when it lands almost exactly on top of another one. */
    private fun stagger(tool: Tool) {
        val window = windows[tool] ?: return
        val mine = rectOf(window)
        var moved = false
        for ((other, w) in windows) {
            if (other == tool) continue
            val r = rectOf(w)
            if (intersectionArea(mine, r) > 0.8f * (mine.width() * mine.height())) {
                mine.offset((24 * density).toInt(), (24 * density).toInt())
                moved = true
            }
        }
        if (moved) {
            val screen = screenSize()
            if (mine.right > screen.x || mine.bottom > screen.y) {
                mine.offsetTo((8 * density).toInt(), (60 * density).toInt())
            }
            window.applyGeometry(
                WindowGeometry(
                    (mine.left / density).toInt(),
                    (mine.top / density).toInt(),
                    (mine.width() / density).toInt(),
                    (mine.height() / density).toInt()
                ),
                screen
            )
        }
    }

    private fun rectOf(window: ToolWindow): Rect = Rect(
        window.params.x,
        window.params.y,
        window.params.x + window.params.width,
        window.params.y + window.params.height
    )

    private fun intersectionArea(a: Rect, b: Rect): Int {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0
        return (right - left) * (bottom - top)
    }

    fun otherRects(exclude: Tool): List<SnapRect> {
        val out = ArrayList<SnapRect>(windows.size)
        for ((tool, window) in windows) {
            if (tool == exclude) continue
            val rect = rectOf(window)
            out.add(SnapRect(rect.left, rect.top, rect.right, rect.bottom))
        }
        return out
    }

    fun applyPrefsToAll() {
        for (window in windows.values) window.applyPrefs()
    }

    /** Restores every window to its default position/size and unlocks touch. */
    fun resetLayout() {
        val screen = screenSize()
        for (tool in Tool.entries) {
            prefs.setToolLocked(tool, false)
            prefs.setGeometry(tool, clampGeometry(tool.defaultGeometry(), tool))
        }
        for ((tool, window) in windows) {
            window.applyPrefs()
            window.applyGeometry(clampGeometry(tool.defaultGeometry(), tool), screen)
        }
    }

    fun screenSize(): Point {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            return try {
                val bounds = wm.currentWindowMetrics.bounds
                Point(bounds.width(), bounds.height())
            } catch (t: Throwable) {
                fallbackScreenSize()
            }
        }
        return fallbackScreenSize()
    }

    private fun fallbackScreenSize(): Point {
        val dm = context.resources.displayMetrics
        return Point(dm.widthPixels, dm.heightPixels)
    }

    private fun clampGeometry(geometry: WindowGeometry, tool: Tool): WindowGeometry {
        val screen = screenSize()
        val w = (geometry.w * density).toInt()
            .coerceIn((tool.minWidthDp * density).toInt(), screen.x)
        val h = (geometry.h * density).toInt()
            .coerceIn((tool.minHeightDp * density).toInt(), screen.y)
        val x = (geometry.x * density).toInt().coerceIn(0, max(0, screen.x - (80 * density).toInt()))
        val y = (geometry.y * density).toInt().coerceIn(0, max(0, screen.y - (80 * density).toInt()))
        val maxX = max(0, screen.x - w)
        val maxY = max(0, screen.y - h)
        return WindowGeometry(
            (min(x, maxX) / density).toInt(),
            (min(y, maxY) / density).toInt(),
            (w / density).toInt(),
            (h / density).toInt()
        )
    }

    /** Shows the floating per-tool settings, anchored just under that tool's window. */
    private fun openQuickSettings(tool: Tool, window: ToolWindow) {
        val wm = windowManager ?: return
        val screen = screenSize()
        val panel = quickSettings ?: QuickSettingsPanel(context, wm, prefs).also { quickSettings = it }
        val anchorY = window.params.y + window.params.height + (10 * density).toInt()
        panel.toggle(tool, screen, anchorY)
    }

    private fun openSettings() {
        try {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        } catch (t: Throwable) {
            // ignore
        }
    }

    override fun onPrefsChanged(keys: Set<String>) {
        if (keys.any { it.startsWith("tool_") }) {
            syncVisibility()
            onVisibilityChanged?.invoke()
            return
        }
        // Cheap enough to always run, and it guarantees the link/lock icons are never stale.
        applyPrefsToAll()
    }

    fun release() {
        quickSettings?.dismiss()
        quickSettings = null
        prefs.removeListener(this)
        for (tool in windows.keys.toList()) remove(tool)
        windows.clear()
    }

    fun attach() {
        prefs.addListener(this)
    }
}

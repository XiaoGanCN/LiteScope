package com.litescope.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup.LayoutParams as AndroidLayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.litescope.R
import com.litescope.core.Prefs
import com.litescope.core.Tool
import com.litescope.core.WindowGeometry
import com.litescope.view.MeterView
import com.litescope.view.ScopeTheme
import com.litescope.view.ScopeView
import com.litescope.view.SpectrumView
import com.litescope.view.VectorScopeView
import com.litescope.view.WaterfallView
import com.litescope.view.WaveformView
import kotlin.math.max
import kotlin.math.min

/**
 * One floating scope window: a slim title bar with monochrome action icons, the scope itself and
 * eight invisible resize handles around the frame.
 *
 * When the window is too narrow for the icons, the title bar collapses them into a single "more"
 * button that pops out a floating pill of actions over the scope, so a slim window never loses its
 * controls.
 */
class ToolWindow(
    private val context: Context,
    val tool: Tool,
    private val prefs: Prefs,
    private val onClose: (Tool) -> Unit,
    private val onOpenSettings: () -> Unit
) {

    /** Rectangles of the other visible windows, used for magnetic snapping. */
    var otherRectsProvider: (() -> List<SnapRect>)? = null

    /** Current screen size in pixels. */
    var screenProvider: (() -> Point)? = null

    /** Called when the user finishes moving/resizing so the geometry can be persisted. */
    var onGeometryCommitted: ((Tool, WindowGeometry) -> Unit)? = null

    val content: View = createContent(context, tool)
    val root: RootView

    lateinit var params: WindowManager.LayoutParams
        private set

    private val density = context.resources.displayMetrics.density
    private val chrome: LinearLayout
    private val inlineActions: LinearLayout
    private val moreButton: ImageView
    private val titleView: TextView
    private val lockButton: ImageView
    private val linkButton: ImageView?

    private var compact = false
    private var popup: PopupWindow? = null

    init {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        chrome = LinearLayout(context)
        chrome.orientation = LinearLayout.HORIZONTAL
        chrome.gravity = Gravity.CENTER_VERTICAL
        chrome.setBackgroundColor(ContextCompat.getColor(context, R.color.panel_alt))

        titleView = TextView(context)
        titleView.setTextAppearance(R.style.ToolTitle)
        titleView.text = tool.title
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        titleView.textSize = 11.5f
        titleView.setPadding(dpInt(9), 0, dpInt(6), 0)
        titleView.isClickable = false
        chrome.addView(titleView, LinearLayout.LayoutParams(0, dpInt(30), 1f))

        lockButton = iconButton(R.drawable.ic_lock_open) { prefs.setToolLocked(tool, true) }
        linkButton = if (supportsLink()) {
            iconButton(R.drawable.ic_link_off) { toggleLink() }
        } else {
            null
        }

        inlineActions = LinearLayout(context)
        inlineActions.orientation = LinearLayout.HORIZONTAL
        inlineActions.gravity = Gravity.CENTER_VERTICAL
        inlineActions.addView(lockButton, iconParams())
        linkButton?.let { inlineActions.addView(it, iconParams()) }
        inlineActions.addView(
            iconButton(R.drawable.ic_refresh) { (content as? ScopeView)?.resetView() },
            iconParams()
        )
        inlineActions.addView(iconButton(R.drawable.ic_settings) { openQuickSettings() }, iconParams())
        inlineActions.addView(iconButton(R.drawable.ic_close) { onClose(tool) }, iconParams())
        chrome.addView(
            inlineActions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpInt(30))
        )

        moreButton = iconButton(R.drawable.ic_more_horiz) { showActionPopup() }
        moreButton.visibility = View.GONE
        chrome.addView(moreButton, iconParams())

        column.addView(chrome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(30)))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root = RootView(context, column)
        root.background = ContextCompat.getDrawable(context, R.drawable.bg_window)
        root.clipToOutline = true
    }

    private fun supportsLink(): Boolean = tool == Tool.SPECTRUM || tool == Tool.WATERFALL

    /** Opens the per-tool quick settings panel (falls back to the activity if unavailable). */
    private fun openQuickSettings() {
        val handler = onOpenQuickSettings
        if (handler != null) handler(tool) else onOpenSettings()
    }

    private fun toggleLink() {
        prefs.linkZoom = !prefs.linkZoom
        applyPrefs()
    }

    /**
     * Pops the window actions out into their own tiny window anchored to the "more" button, so a
     * very narrow scope window can still reach every action without clipping them.
     */
    private fun showActionPopup() {
        dismissPopup()
        val p = ScopeTheme.palette(prefs)
        val dim = ContextCompat.getColor(context, R.color.text_dim)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // Themed pill: panel fill with an accent outline, matching the window chrome.
        row.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpF(18f)
            setColor(p.panel)
            setStroke(dpF(1f).toInt().coerceAtLeast(1), p.alpha(p.accent, 0.55f))
        }
        row.setPadding(dpInt(5), dpInt(5), dpInt(5), dpInt(5))
        row.elevation = dpF(8f)

        row.addView(
            iconButton(R.drawable.ic_lock_open) {
                prefs.setToolLocked(tool, !prefs.toolLocked(tool))
                dismissPopup()
            }.also { it.setColorFilter(if (prefs.toolLocked(tool)) p.accent else dim) },
            popupParams()
        )
        if (supportsLink()) {
            row.addView(
                iconButton(if (prefs.linkZoom) R.drawable.ic_link else R.drawable.ic_link_off) {
                    toggleLink()
                    dismissPopup()
                }.also { it.setColorFilter(if (prefs.linkZoom) p.accent else dim) },
                popupParams()
            )
        }
        row.addView(
            iconButton(R.drawable.ic_refresh) {
                (content as? ScopeView)?.resetView()
                dismissPopup()
            }.also { it.setColorFilter(dim) },
            popupParams()
        )
        row.addView(
            iconButton(R.drawable.ic_settings) {
                dismissPopup()
                openQuickSettings()
            }.also { it.setColorFilter(dim) },
            popupParams()
        )
        row.addView(
            iconButton(R.drawable.ic_close) {
                dismissPopup()
                onClose(tool)
            }.also { it.setColorFilter(dim) },
            popupParams()
        )

        val window = PopupWindow(row, AndroidLayoutParams.WRAP_CONTENT, AndroidLayoutParams.WRAP_CONTENT, true)
        window.isOutsideTouchable = true
        window.isFocusable = false
        window.elevation = dpF(10f)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        try {
            row.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            // Right-align the popup with the button so it grows inwards over the scope.
            val offsetX = moreButton.width - row.measuredWidth
            window.showAsDropDown(moreButton, offsetX, dpInt(2))
            popup = window
        } catch (t: Throwable) {
            // Some devices refuse sub-windows from a service context: fall back to inline actions.
            popup = null
            inlineActions.visibility = View.VISIBLE
            moreButton.visibility = View.GONE
        }
    }

    private fun dismissPopup() {
        try {
            popup?.dismiss()
        } catch (t: Throwable) {
            // ignore
        }
        popup = null
    }

    private fun iconButton(iconRes: Int, action: () -> Unit): ImageView {
        val view = ImageView(context)
        view.setImageResource(iconRes)
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.setPadding(dpInt(6), dpInt(5), dpInt(6), dpInt(5))
        view.isClickable = true
        view.setOnClickListener { action() }
        return view
    }

    private fun iconParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dpInt(32), dpInt(30))

    private fun popupParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dpInt(42), dpInt(40))

    /** Updates icon tints, lock state, compact/inline mode and window alpha. */
    fun applyPrefs() {
        val locked = prefs.toolLocked(tool)
        val p = ScopeTheme.palette(prefs)
        val dim = ContextCompat.getColor(context, R.color.text_dim)
        lockButton.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        lockButton.setColorFilter(if (locked) p.accent else dim)

        if (linkButton != null) {
            val linked = prefs.linkZoom
            linkButton.setImageResource(if (linked) R.drawable.ic_link else R.drawable.ic_link_off)
            linkButton.setColorFilter(if (linked) p.accent else dim)
            linkButton.contentDescription = if (linked) "Unlink from spectrum" else "Link with spectrum"
        }
        if (popup?.isShowing == true) dismissPopup()

        titleView.setTextColor(if (locked) p.accent else dim)
        // The title bar fades with the window but keeps a readable floor.
        chrome.setBackgroundColor(p.alpha(p.panel, (prefs.overlayOpacity / 100f).coerceAtLeast(0.45f)))
        chrome.visibility = if (prefs.windowChrome) View.VISIBLE else View.GONE
        if (::params.isInitialized) {
            // The whole window stays opaque: transparency is applied to the scope background only,
            // so the traces, cursors and readouts keep full contrast.
            params.alpha = 1f
            val flags = baseFlags()
            params.flags = if (locked) flags or FLAG_NOT_TOUCHABLE else flags
            titleView.text = if (locked) "${tool.title} · locked" else tool.title
        }
        applyChromeMode()
    }

    /** Compact mode hides the inline icons behind a single pop-out button. */
    private fun applyChromeMode() {
        val useCompact = compact && prefs.windowChrome
        if (useCompact == (inlineActions.visibility == View.GONE)) return
        if (useCompact) dismissPopup()
        inlineActions.visibility = if (useCompact) View.GONE else View.VISIBLE
        moreButton.visibility = if (useCompact) View.VISIBLE else View.GONE
        // A small fade keeps the swap from flashing when the window is resized.
        val shown = if (useCompact) moreButton else inlineActions
        shown.alpha = 0f
        shown.animate().alpha(1f).setDuration(140L).start()
    }

    fun setCompact(value: Boolean) {
        if (compact != value) {
            compact = value
            applyChromeMode()
        }
    }

    /** Smallest width this tool is allowed to be resized to. */
    private fun minWidthDp(): Int = tool.minWidthDp

    private fun minHeightDp(): Int = tool.minHeightDp

    private fun baseFlags(): Int =
        FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_LAYOUT_IN_SCREEN or FLAG_HARDWARE_ACCELERATED

    fun buildParams(geometry: WindowGeometry, screen: Point): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            (geometry.w * density).toInt().coerceAtLeast(dpInt(minWidthDp())),
            (geometry.h * density).toInt().coerceAtLeast(dpInt(minHeightDp())),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (geometry.x * density).toInt()
        p.y = (geometry.y * density).toInt()
        p.setTitle("LiteScope ${tool.title}")
        p.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        params = p
        applyPrefs()
        return p
    }

    fun geometry(): WindowGeometry = WindowGeometry(
        (params.x / density).toInt(),
        (params.y / density).toInt(),
        (params.width / density).toInt(),
        (params.height / density).toInt()
    )

    /** Moves/resizes the window to the given dp geometry. */
    fun applyGeometry(geometry: WindowGeometry, screen: Point) {
        if (!::params.isInitialized) return
        val w = (geometry.w * density).toInt().coerceIn(dpInt(minWidthDp()), screen.x)
        val h = (geometry.h * density).toInt().coerceIn(dpInt(minHeightDp()), screen.y)
        params.width = w
        params.height = h
        params.x = (geometry.x * density).toInt().coerceIn(0, max(0, screen.x - dpInt(40)))
        params.y = (geometry.y * density).toInt().coerceIn(0, max(0, screen.y - dpInt(40)))
        pushLayout()
    }

    /** Dismisses any open action popup (called when the window goes away). */
    fun releasePopups() {
        dismissPopup()
    }

    fun pushLayout() {
        val wm = windowManagerRef ?: return
        if (!::params.isInitialized) return
        try {
            wm.updateViewLayout(root, params)
        } catch (t: Throwable) {
            // window already removed
        }
    }

    /** Set by [OverlayManager] before the view is added to the window manager. */
    var windowManagerRef: WindowManager? = null

    /** Opens the floating quick settings for this tool (provided by [OverlayManager]). */
    var onOpenQuickSettings: ((Tool) -> Unit)? = null

    private fun dpInt(value: Int): Int = (value * density).toInt()

    private fun dpF(value: Float): Float = value * density

    private fun createContent(context: Context, tool: Tool): View = when (tool) {
        Tool.SPECTRUM -> SpectrumView(context)
        Tool.WATERFALL -> WaterfallView(context)
        Tool.WAVEFORM -> WaveformView(context)
        Tool.VECTOR -> VectorScopeView(context)
        Tool.METERS -> MeterView(context)
    }

    /** Root container handling the window chrome gestures. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    inner class RootView(context: Context, child: View) : FrameLayout(context) {

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderRect = RectF()
        private var highlight = false

        private var mode = MODE_NONE
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var startH = 0

        init {
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            isClickable = true
            setWillNotDraw(false)
            borderPaint.style = Paint.Style.STROKE
            borderPaint.strokeWidth = dpF(1.4f)
            borderPaint.color = ContextCompat.getColor(context, R.color.stroke)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            setCompact(r - l < dpInt(COMPACT_WIDTH_DP))
        }

        private fun setSnapHighlight(value: Boolean) {
            if (highlight != value) {
                highlight = value
                borderPaint.color = ContextCompat.getColor(
                    context,
                    if (value) R.color.accent else R.color.stroke
                )
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = dpF(WINDOW_RADIUS_DP)
            // Soft outer glow: the same rounded outline drawn a few times, wider and fainter.
            for (i in GLOW_WIDTHS.indices) {
                borderPaint.strokeWidth = dpF(GLOW_WIDTHS[i])
                borderPaint.alpha = GLOW_ALPHAS[i]
                val inset = borderPaint.strokeWidth / 2f
                borderRect.set(inset, inset, width - inset, height - inset)
                canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
            }
            borderPaint.strokeWidth = dpF(1.4f)
            borderPaint.alpha = 255
            val inset = borderPaint.strokeWidth / 2f
            borderRect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
        }

        /** True when the touch lands on the draggable part of the title bar. */
        private fun inTitleBar(x: Float, y: Float): Boolean {
            if (!prefs.windowChrome || chrome.visibility != View.VISIBLE) return false
            if (y < 0f || y > chrome.height.toFloat()) return false
            return x >= 0f && x <= titleView.right
        }

        private fun resizeModeAt(x: Float, y: Float): Int {
            if (prefs.toolLocked(tool) || !prefs.windowChrome) return MODE_NONE
            // Narrow band: cursors dragged to the scope edge used to be stolen by the resize handle.
            val band = dpF(7f)
            val nearLeft = x <= band
            val nearRight = x >= width - band
            val nearTop = y <= band
            val nearBottom = y >= height - band
            return when {
                nearLeft && nearTop -> MODE_RESIZE_TL
                nearRight && nearTop -> MODE_RESIZE_TR
                nearLeft && nearBottom -> MODE_RESIZE_BL
                nearRight && nearBottom -> MODE_RESIZE_BR
                nearLeft -> MODE_RESIZE_L
                nearRight -> MODE_RESIZE_R
                nearTop -> MODE_RESIZE_T
                nearBottom -> MODE_RESIZE_B
                else -> MODE_NONE
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (prefs.toolLocked(tool)) return false
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                mode = if (inTitleBar(ev.x, ev.y)) {
                    MODE_MOVE
                } else {
                    resizeModeAt(ev.x, ev.y)
                }
                return mode != MODE_NONE
            }
            return mode != MODE_NONE
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (prefs.toolLocked(tool)) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    if (mode == MODE_NONE) {
                        mode = if (inTitleBar(event.x, event.y)) MODE_MOVE else resizeModeAt(event.x, event.y)
                    }
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    startW = params.width
                    startH = params.height
                    return mode != MODE_NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == MODE_NONE) return false
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (mode == MODE_MOVE) moveTo(startX + dx, startY + dy) else resizeTo(dx, dy)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode != MODE_NONE) {
                        mode = MODE_NONE
                        setSnapHighlight(false)
                        onGeometryCommitted?.invoke(tool, geometry())
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        private fun screen(): Point = screenProvider?.invoke() ?: Point(1080, 1920)

        private fun moveTo(rawX: Int, rawY: Int) {
            val screen = screen()
            val others = otherRectsProvider?.invoke() ?: emptyList()
            val threshold = if (prefs.snapEnabled) (prefs.snapThresholdDp * density).toInt() else 0
            val snapped = WindowSnapper.snap(
                x = rawX,
                y = rawY,
                w = params.width,
                h = params.height,
                others = others,
                screenW = screen.x,
                screenH = screen.y,
                threshold = threshold,
                matchSize = prefs.snapMatchSize
            )
            if (snapped.h != params.height) params.height = snapped.h
            if (snapped.w != params.width) params.width = snapped.w
            val minVisible = dpInt(36)
            params.x = snapped.x.coerceIn(-params.width + minVisible, screen.x - minVisible)
            params.y = snapped.y.coerceIn(0, max(0, screen.y - dpInt(30)))
            setSnapHighlight(snapped.snappedX || snapped.snappedY)
            pushLayout()
        }

        private fun resizeTo(dx: Int, dy: Int) {
            val screen = screen()
            val minW = dpInt(minWidthDp())
            val minH = dpInt(minHeightDp())
            var x = startX
            var y = startY
            var w = startW
            var h = startH
            when (mode) {
                MODE_RESIZE_R, MODE_RESIZE_TR, MODE_RESIZE_BR -> w = startW + dx
                MODE_RESIZE_L, MODE_RESIZE_TL, MODE_RESIZE_BL -> {
                    w = startW - dx
                    x = startX + dx
                }
            }
            when (mode) {
                MODE_RESIZE_B, MODE_RESIZE_BL, MODE_RESIZE_BR -> h = startH + dy
                MODE_RESIZE_T, MODE_RESIZE_TL, MODE_RESIZE_TR -> {
                    h = startH - dy
                    y = startY + dy
                }
            }
            if (w < minW) {
                if (mode == MODE_RESIZE_L || mode == MODE_RESIZE_TL || mode == MODE_RESIZE_BL) {
                    x = startX + (startW - minW)
                }
                w = minW
            }
            if (h < minH) {
                if (mode == MODE_RESIZE_T || mode == MODE_RESIZE_TL || mode == MODE_RESIZE_TR) {
                    y = startY + (startH - minH)
                }
                h = minH
            }
            w = min(w, screen.x)
            h = min(h, screen.y)
            x = x.coerceIn(0, max(0, screen.x - dpInt(60)))
            y = y.coerceIn(0, max(0, screen.y - dpInt(40)))
            params.x = x
            params.y = y
            params.width = w
            params.height = h
            pushLayout()
        }
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE_L = 2
        private const val MODE_RESIZE_R = 3
        private const val MODE_RESIZE_T = 4
        private const val MODE_RESIZE_B = 5
        private const val MODE_RESIZE_TL = 6
        private const val MODE_RESIZE_TR = 7
        private const val MODE_RESIZE_BL = 8
        private const val MODE_RESIZE_BR = 9

        /** Window corner radius, matched by the root outline. */
        const val WINDOW_RADIUS_DP = 12f

        /** Below this width the title bar collapses to a single pop-out button. */
        private const val COMPACT_WIDTH_DP = 250

        /** Glow profile of the window outline (width in dp, alpha 0..255). */
        private val GLOW_WIDTHS = floatArrayOf(7f, 4.5f)
        private val GLOW_ALPHAS = intArrayOf(26, 60)

        private const val FLAG_NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        private const val FLAG_NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        private const val FLAG_LAYOUT_NO_LIMITS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        private const val FLAG_LAYOUT_IN_SCREEN = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        private const val FLAG_HARDWARE_ACCELERATED = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}

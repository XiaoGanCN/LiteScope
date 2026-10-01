package com.litescope.overlay

/**
 * Minimal rectangle used by [WindowSnapper] so the snapping maths stays free of Android types and
 * can be unit tested on the JVM. [OverlayManager] converts window rectangles into this type.
 */
data class SnapRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

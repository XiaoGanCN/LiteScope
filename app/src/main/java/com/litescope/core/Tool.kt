package com.litescope.core

/**
 * A tool window LiteScope can display. Each entry carries the defaults used the first time the
 * window is shown; the user's own position and size are persisted through [Prefs.setGeometry].
 */
enum class Tool(
    val id: String,
    val title: String,
    val defaultW: Int,
    val defaultH: Int,
    val defaultX: Int,
    val defaultY: Int
) {
    SPECTRUM("spectrum", "Spectrum", 360, 190, 8, 60),
    WATERFALL("waterfall", "Waterfall", 360, 240, 8, 520),
    WAVEFORM("waveform", "Wave", 360, 170, 376, 60),
    VECTOR("vector", "Vector", 220, 220, 376, 300),
    METERS("meters", "Meters", 150, 230, 604, 60);

    /** Default geometry in dp. */
    fun defaultGeometry(): WindowGeometry =
        WindowGeometry(defaultX, defaultY, defaultW, defaultH)

    /** Smallest width this tool stays usable at (meters can be very slim). */
    val minWidthDp: Int
        get() = when (this) {
            METERS -> 74
            VECTOR -> 96
            else -> 120
        }

    /** Smallest height this tool stays usable at. */
    val minHeightDp: Int
        get() = if (this == METERS) 90 else 80

    companion object {
        fun fromId(id: String?): Tool? = entries.firstOrNull { it.id == id }

        /** Tools shown in the notification panel, in order. */
        val notificationOrder: List<Tool> = listOf(SPECTRUM, WATERFALL, WAVEFORM, VECTOR, METERS)
    }
}

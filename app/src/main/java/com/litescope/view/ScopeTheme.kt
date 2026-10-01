package com.litescope.view

import android.graphics.Color
import com.litescope.core.Prefs

/** Resolved colour set for the scope views, derived from the accent + background prefs. */
class Palette(
    val accent: Int,
    val accentAlt: Int,
    val accentSoft: Int,
    val bg: Int,
    val grid: Int,
    val gridMajor: Int,
    val text: Int,
    val dim: Int,
    val good: Int,
    val warn: Int,
    val bad: Int,
    val panel: Int,
    val isLight: Boolean
) {
    /** Peak-hold / secondary trace colour. */
    val hold: Int get() = accentAlt

    fun alpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    fun scale(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }
}

/**
 * Design tokens for the scope surfaces and the settings page: eight accents crossed with eight
 * background colours, each background carrying its own grid, text and panel treatments.
 */
object ScopeTheme {

    private class Background(
        val label: String,
        val fill: Int,
        val grid: Int,
        val gridMajor: Int,
        val text: Int,
        val dim: Int,
        val panel: Int,
        val isLight: Boolean
    )

    private val ACCENTS = intArrayOf(
        0xFF35D6C4.toInt(), // teal
        0xFFFFB020.toInt(), // amber
        0xFFA78BFA.toInt(), // violet
        0xFF62D0FF.toInt(), // ice
        0xFFE8EDF2.toInt(), // mono
        0xFFFF7A9A.toInt(), // rose
        0xFFA3E635.toInt(), // lime
        0xFFFF8A4C.toInt() // ember
    )

    private val ACCENT_ALTS = intArrayOf(
        0xFFFF7A9A.toInt(),
        0xFF7BD88F.toInt(),
        0xFF62D0FF.toInt(),
        0xFFFFB020.toInt(),
        0xFF8A97A5.toInt(),
        0xFF7BD8C0.toInt(),
        0xFF62D0FF.toInt(),
        0xFFFFD166.toInt()
    )

    private val BACKGROUNDS = arrayOf(
        Background(
            "Deep black", 0xF006080B.toInt(), 0xFF1B232C.toInt(), 0xFF2E3C4A.toInt(),
            0xFFE6EDF3.toInt(), 0xFF8A97A5.toInt(), 0xCC10151A.toInt(), false
        ),
        Background(
            "Graphite", 0xFF14181D.toInt(), 0xFF2A3138.toInt(), 0xFF3D4753.toInt(),
            0xFFE6EDF3.toInt(), 0xFF93A1B1.toInt(), 0xCC1B2129.toInt(), false
        ),
        Background(
            "OLED", 0xFF000000.toInt(), 0xFF171717.toInt(), 0xFF2B2B2B.toInt(),
            0xFFEDEDED.toInt(), 0xFF8C8C8C.toInt(), 0xCC0D0D0D.toInt(), false
        ),
        Background(
            "Midnight", 0xFF070E1A.toInt(), 0xFF16243A.toInt(), 0xFF27405F.toInt(),
            0xFFDCE8F5.toInt(), 0xFF8FA6BF.toInt(), 0xCC0C1626.toInt(), false
        ),
        Background(
            "Plum", 0xFF120A18.toInt(), 0xFF2A1836.toInt(), 0xFF43265A.toInt(),
            0xFFF0E6F7.toInt(), 0xFFA992BC.toInt(), 0xCC180F22.toInt(), false
        ),
        Background(
            "Forest", 0xFF08130F.toInt(), 0xFF163026.toInt(), 0xFF244C3C.toInt(),
            0xFFE1F2EA.toInt(), 0xFF93B5A7.toInt(), 0xCC0D1C17.toInt(), false
        ),
        Background(
            "Paper", 0xFFF4F6F8.toInt(), 0xFFD8DEE6.toInt(), 0xFFAEB8C4.toInt(),
            0xFF1B2129.toInt(), 0xFF5D6A78.toInt(), 0xE6FFFFFF.toInt(), true
        ),
        Background(
            "Sand", 0xFFF6F1E7.toInt(), 0xFFDED5C4.toInt(), 0xFFB8AA90.toInt(),
            0xFF2A241A.toInt(), 0xFF6E6350.toInt(), 0xE6FFFDF7.toInt(), true
        )
    )

    private val cache = HashMap<Int, Palette>()

    val accentNames: List<String> =
        listOf("Teal", "Amber", "Violet", "Ice", "Mono", "Rose", "Lime", "Ember")

    val backgroundNames: List<String> = BACKGROUNDS.map { it.label }

    fun accentName(index: Int): String = accentNames.getOrElse(index) { accentNames[0] }

    fun backgroundName(index: Int): String = backgroundNames.getOrElse(index) { backgroundNames[0] }

    val accentCount: Int get() = ACCENTS.size

    val backgroundCount: Int get() = BACKGROUNDS.size

    /** Colour of an accent, for drawing swatches in the settings UI. */
    fun accentColor(index: Int): Int = ACCENTS[index.coerceIn(0, ACCENTS.size - 1)]

    /** True when the background is one of the light treatments. */
    fun isLightBackground(index: Int): Boolean =
        BACKGROUNDS[index.coerceIn(0, BACKGROUNDS.size - 1)].isLight

    /** Fill colour of a background, for drawing swatches in the settings UI. */
    fun backgroundColor(index: Int): Int =
        BACKGROUNDS[index.coerceIn(0, BACKGROUNDS.size - 1)].fill

    @Synchronized
    fun palette(prefs: Prefs): Palette {
        val accentIndex = prefs.accentTheme.coerceIn(0, ACCENTS.size - 1)
        val bgIndex = prefs.scopeBackground.coerceIn(0, BACKGROUNDS.size - 1)
        val key = accentIndex * 16 + bgIndex
        cache[key]?.let { return it }

        val rawAccent = ACCENTS[accentIndex]
        val rawAlt = ACCENT_ALTS[accentIndex]
        val bg = BACKGROUNDS[bgIndex]
        val accent = if (bg.isLight) darken(rawAccent) else rawAccent
        val alt = if (bg.isLight) darken(rawAlt) else rawAlt
        val palette = Palette(
            accent = accent,
            accentAlt = alt,
            accentSoft = withAlpha(accent, 0x2E),
            bg = bg.fill,
            grid = bg.grid,
            gridMajor = bg.gridMajor,
            text = bg.text,
            dim = bg.dim,
            good = if (bg.isLight) 0xFF1F9D55.toInt() else 0xFF6BE675.toInt(),
            warn = if (bg.isLight) 0xFFC77700.toInt() else 0xFFFFB020.toInt(),
            bad = if (bg.isLight) 0xFFD64545.toInt() else 0xFFFF5C5C.toInt(),
            panel = bg.panel,
            isLight = bg.isLight
        )
        cache[key] = palette
        return palette
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun darken(color: Int): Int {
        val r = (Color.red(color) * 0.62f).toInt()
        val g = (Color.green(color) * 0.62f).toInt()
        val b = (Color.blue(color) * 0.62f).toInt()
        return Color.argb(Color.alpha(color), r, g, b)
    }
}

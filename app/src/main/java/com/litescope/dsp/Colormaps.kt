package com.litescope.dsp

import android.graphics.Color

/** Waterfall colour ramps. */
enum class Colormap(val label: String) {
    MAGMA("Magma"),
    VIRIDIS("Viridis"),
    INFERNO("Inferno"),
    FIRE("Fire"),
    ICE("Ice"),
    RAINBOW("Rainbow"),
    GRAYSCALE("Grayscale");

    companion object {
        fun fromOrdinal(value: Int): Colormap = entries[value.coerceIn(0, entries.size - 1)]
    }
}

object Colormaps {

    /** Number of entries in each lookup table. */
    const val LEVELS = 256

    // Control points: position (0..1) followed by r, g, b (0..255).
    private val MAGMA = floatArrayOf(
        0.00f, 0f, 0f, 4f,
        0.13f, 28f, 16f, 68f,
        0.25f, 79f, 18f, 123f,
        0.38f, 129f, 37f, 129f,
        0.50f, 181f, 54f, 122f,
        0.63f, 229f, 80f, 100f,
        0.75f, 251f, 135f, 97f,
        0.88f, 254f, 194f, 135f,
        1.00f, 252f, 253f, 191f
    )

    private val VIRIDIS = floatArrayOf(
        0.00f, 68f, 1f, 84f,
        0.13f, 72f, 40f, 120f,
        0.25f, 59f, 82f, 139f,
        0.38f, 44f, 113f, 142f,
        0.50f, 33f, 145f, 140f,
        0.63f, 39f, 173f, 129f,
        0.75f, 92f, 200f, 99f,
        0.88f, 170f, 220f, 50f,
        1.00f, 253f, 231f, 37f
    )

    private val INFERNO = floatArrayOf(
        0.00f, 0f, 0f, 4f,
        0.13f, 31f, 12f, 72f,
        0.25f, 85f, 15f, 109f,
        0.38f, 136f, 34f, 106f,
        0.50f, 186f, 54f, 85f,
        0.63f, 227f, 89f, 51f,
        0.75f, 249f, 140f, 10f,
        0.88f, 249f, 201f, 50f,
        1.00f, 252f, 255f, 164f
    )

    private val FIRE = floatArrayOf(
        0.00f, 0f, 0f, 0f,
        0.20f, 70f, 0f, 0f,
        0.40f, 160f, 20f, 0f,
        0.60f, 240f, 90f, 0f,
        0.80f, 255f, 200f, 40f,
        1.00f, 255f, 255f, 235f
    )

    private val ICE = floatArrayOf(
        0.00f, 0f, 0f, 8f,
        0.20f, 0f, 30f, 105f,
        0.40f, 0f, 110f, 190f,
        0.60f, 0f, 190f, 225f,
        0.80f, 130f, 235f, 250f,
        1.00f, 255f, 255f, 255f
    )

    private val GRAY = floatArrayOf(
        0.00f, 0f, 0f, 0f,
        1.00f, 255f, 255f, 255f
    )

    private val cache = HashMap<Colormap, IntArray>()

    /** 256 entry ARGB lookup table for [map], built once and cached. */
    @Synchronized
    fun lut(map: Colormap): IntArray = cache.getOrPut(map) {
        val out = IntArray(LEVELS)
        for (i in 0 until LEVELS) {
            val t = i.toFloat() / (LEVELS - 1)
            val rgb = if (map == Colormap.RAINBOW) rainbow(t) else ramp(stops(map), t)
            out[i] = Color.rgb(rgb[0], rgb[1], rgb[2])
        }
        out
    }

    private fun stops(map: Colormap): FloatArray = when (map) {
        Colormap.MAGMA -> MAGMA
        Colormap.VIRIDIS -> VIRIDIS
        Colormap.INFERNO -> INFERNO
        Colormap.FIRE -> FIRE
        Colormap.ICE -> ICE
        Colormap.GRAYSCALE -> GRAY
        Colormap.RAINBOW -> GRAY // unused
    }

    private fun ramp(stops: FloatArray, t: Float): IntArray {
        var i = 0
        while (i + 4 < stops.size && t > stops[i + 4]) i += 4
        val p0 = stops[i]
        val next = i + 4
        if (next >= stops.size) {
            return intArrayOf(stops[i + 1].toInt(), stops[i + 2].toInt(), stops[i + 3].toInt())
        }
        val p1 = stops[next]
        val f = if (p1 - p0 <= 0f) 0f else ((t - p0) / (p1 - p0)).coerceIn(0f, 1f)
        return intArrayOf(
            (stops[i + 1] + (stops[next + 1] - stops[i + 1]) * f).toInt().coerceIn(0, 255),
            (stops[i + 2] + (stops[next + 2] - stops[i + 2]) * f).toInt().coerceIn(0, 255),
            (stops[i + 3] + (stops[next + 3] - stops[i + 3]) * f).toInt().coerceIn(0, 255)
        )
    }

    /** Blue -> cyan -> green -> yellow -> red hue sweep. */
    private fun rainbow(t: Float): IntArray {
        val hue = (240f * (1f - t.coerceIn(0f, 1f)))
        return hsvToRgb(hue, 1f, 1f)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): IntArray {
        val c = v * s
        val hh = (h / 60f) % 6f
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return intArrayOf(
            ((r1 + m) * 255f).toInt().coerceIn(0, 255),
            ((g1 + m) * 255f).toInt().coerceIn(0, 255),
            ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        )
    }
}

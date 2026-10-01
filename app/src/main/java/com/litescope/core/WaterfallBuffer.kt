package com.litescope.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.litescope.dsp.Colormap
import com.litescope.dsp.Colormaps
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scrolling spectrogram history.
 *
 * Spectra arrive as dBFS bins and are written as coloured rows into a single Bitmap that acts as
 * a circular buffer in the vertical direction: new rows are written at [nextWrite] moving towards
 * increasing y, so row `y` is always *older* than row `y + 1` (with one wrap). Rendering draws the
 * wrapped block in chronological order and mirrors it vertically when the newest row belongs on
 * top, which needs no per-row copies.
 *
 * The bitmap is sized `bins x maxRows` under a fixed pixel budget (see
 * [Prefs.WATERFALL_PIXEL_BUDGET]); the row rate is reduced to honour the requested history length
 * rather than dropping history.
 */
class WaterfallBuffer {

    var bins: Int = 0
        private set

    var rows: Int = 0
        private set

    private var bitmap: Bitmap? = null
    private var rowPixels = IntArray(0)
    private var lut: IntArray = Colormaps.lut(Colormap.MAGMA)
    private var nextWrite = 0
    private var newest = -1

    private val srcRect = Rect()
    private val dstRect = RectF()

    /** True once at least one row has been written. */
    val hasData: Boolean get() = newest >= 0

    fun configure(bins: Int, maxRows: Int) {
        val b = bins.coerceIn(8, 8192)
        val r = maxRows.coerceIn(8, 16384)
        if (b == this.bins && r == this.rows && bitmap != null) return
        bitmap?.recycle()
        this.bins = b
        this.rows = r
        bitmap = Bitmap.createBitmap(b, r, Bitmap.Config.ARGB_8888)
        bitmap?.eraseColor(0xFF000000.toInt())
        rowPixels = IntArray(b)
        nextWrite = 0
        newest = -1
    }

    fun setColormap(map: Colormap) {
        lut = Colormaps.lut(map)
    }

    fun clear() {
        bitmap?.eraseColor(0xFF000000.toInt())
        nextWrite = 0
        newest = -1
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        bins = 0
        rows = 0
        newest = -1
    }

    /** Maps one spectrum frame (dBFS) into a row. */
    fun push(db: FloatArray, count: Int, floorDb: Float, topDb: Float) {
        val bmp = bitmap ?: return
        if (rows <= 0 || bins <= 0) return
        val n = min(count, bins)
        val range = (topDb - floorDb).coerceAtLeast(1f)
        for (i in 0 until n) {
            val t = ((db[i] - floorDb) / range).coerceIn(0f, 1f)
            rowPixels[i] = lut[(t * (Colormaps.LEVELS - 1)).roundToInt().coerceIn(0, Colormaps.LEVELS - 1)]
        }
        bmp.setPixels(rowPixels, 0, bins, 0, nextWrite, n, 1)
        newest = nextWrite
        nextWrite = (nextWrite + 1) % rows
    }

    /**
     * Draws [visibleRows] rows ending at the newest row, one horizontal strip at a time.
     *
     * Each strip carries its own source range, so the history can be mapped through *any* frequency
     * axis - linear, logarithmic or a blend - instead of being stretched linearly across the plot.
     * The caller supplies `[srcLeftFrac, srcRightFrac, dstLeft, dstRight]` per strip in [strips].
     */
    fun render(
        canvas: Canvas,
        top: Float,
        bottom: Float,
        visibleRows: Int,
        newestOnTop: Boolean,
        paint: Paint,
        strips: FloatArray,
        stripCount: Int
    ) {
        val bmp = bitmap ?: return
        if (newest < 0 || rows <= 0 || stripCount <= 0 || bottom <= top) return

        val shown = visibleRows.coerceIn(1, rows)
        val firstY = (((newest - shown + 1) % rows) + rows) % rows
        val part1 = min(shown, rows - firstY)
        val part2 = shown - part1

        canvas.save()
        if (newestOnTop) {
            val last = (stripCount - 1) * 4
            canvas.scale(1f, -1f, (strips[2] + strips[last + 3]) / 2f, (top + bottom) / 2f)
        }
        for (s in 0 until stripCount) {
            val base = s * 4
            val lf = strips[base].coerceIn(0f, 1f)
            val rf = strips[base + 1].coerceIn(0f, 1f)
            val dstLeft = strips[base + 2]
            val dstRight = strips[base + 3]
            if (rf <= lf || dstRight <= dstLeft) continue
            val srcX = (lf * bins).toInt().coerceIn(0, bins - 1)
            val srcRight = (rf * bins).toInt().coerceIn(srcX + 1, bins)
            var y = top
            if (part1 > 0) {
                val h = (bottom - top) * part1 / shown
                srcRect.set(srcX, firstY, srcRight, firstY + part1)
                dstRect.set(dstLeft, y, dstRight, y + h)
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
                y += h
            }
            if (part2 > 0) {
                srcRect.set(srcX, 0, srcRight, part2)
                dstRect.set(dstLeft, y, dstRight, bottom)
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            }
        }
        canvas.restore()
    }
}

package com.litescope.overlay

import kotlin.math.abs

/**
 * Magnetic edge snapping for the floating tool windows.
 *
 * A dragged window is aligned to the screen edges, the screen centre and every edge or centre of
 * the other visible windows when it comes within [threshold] pixels. When a window docks beside
 * another one that overlaps it vertically by more than half, the two are also matched in size
 * (unless [matchSize] is disabled), which is what makes the spectrum and the waterfall line up
 * exactly after snapping.
 */
object WindowSnapper {

    class Result(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val snappedX: Boolean,
        val snappedY: Boolean
    ) {
        override fun toString(): String =
            "Result(x=$x, y=$y, w=$w, h=$h, snappedX=$snappedX, snappedY=$snappedY)"
    }

    fun snap(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        others: List<SnapRect>,
        screenW: Int,
        screenH: Int,
        threshold: Int,
        matchSize: Boolean
    ): Result {
        if (threshold <= 0) return Result(x, y, w, h, false, false)

        var bestX = x
        var bestDx = threshold + 1
        var bestXNeighbour: SnapRect? = null

        var bestY = y
        var bestDy = threshold + 1
        var bestYNeighbour: SnapRect? = null

        fun considerX(candidate: Int, neighbour: SnapRect?) {
            val d = abs(candidate - x)
            if (d < bestDx) {
                bestDx = d
                bestX = candidate
                bestXNeighbour = neighbour
            }
        }

        fun considerY(candidate: Int, neighbour: SnapRect?) {
            val d = abs(candidate - y)
            if (d < bestDy) {
                bestDy = d
                bestY = candidate
                bestYNeighbour = neighbour
            }
        }

        // Screen edges and centre.
        considerX(0, null)
        considerX(screenW - w, null)
        considerX((screenW - w) / 2, null)
        considerY(0, null)
        considerY(screenH - h, null)
        considerY((screenH - h) / 2, null)

        for (other in others) {
            considerX(other.left, other)
            considerX(other.right, other)
            considerX(other.left + (other.width - w) / 2, other)
            considerY(other.top, other)
            considerY(other.bottom, other)
            considerY(other.top + (other.height - h) / 2, other)
        }

        var outW = w
        var outH = h
        var outX = if (bestDx <= threshold) bestX else x
        var outY = if (bestDy <= threshold) bestY else y
        var snappedX = bestDx <= threshold
        var snappedY = bestDy <= threshold

        if (matchSize) {
            val nx = bestXNeighbour
            if (snappedX && nx != null) {
                val overlap = overlapLength(outY, outY + outH, nx.top, nx.bottom)
                if (overlap > outH * 0.5f) {
                    outH = nx.height
                    if (abs(nx.top - outY) <= threshold * 2) {
                        outY = nx.top
                        snappedY = true
                    }
                }
            }
            val ny = bestYNeighbour
            if (snappedY && ny != null) {
                val overlap = overlapLength(outX, outX + outW, ny.left, ny.right)
                if (overlap > outW * 0.5f) {
                    outW = ny.width
                    if (abs(ny.left - outX) <= threshold * 2) outX = ny.left
                }
            }
        }

        return Result(outX, outY, outW, outH, snappedX, snappedY)
    }

    private fun overlapLength(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
        val start = maxOf(aStart, bStart)
        val end = minOf(aEnd, bEnd)
        return (end - start).coerceAtLeast(0)
    }
}

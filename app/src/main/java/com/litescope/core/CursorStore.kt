package com.litescope.core

import android.content.Context
import android.os.SystemClock

/**
 * A measurement cursor (marker) placed on the frequency axis.
 *
 * Cursors are shared by the spectrum and the waterfall scope, so a locked marker stays visible in
 * both when the two tools are linked.
 */
class Cursor(var hz: Float, var locked: Boolean = false) {
    /** Stable identity used by the views to track the cursor being dragged. */
    val id: Int = nextId++

    companion object {
        private var nextId = 1
    }
}

/**
 * Holds the measurement cursors, the interaction timestamp that drives the auto-hide behaviour and
 * a version counter so views can tell whether anything changed.
 *
 * Interaction model (see `SpectrumView`):
 *  * tap on empty space -> add an unlocked cursor
 *  * tap on a cursor -> remove it
 *  * drag near a cursor -> move it
 *  * long press on a cursor -> toggle its lock
 *  * long press on empty space -> clear the peak-hold trace
 *
 * Unlocked cursors disappear [AUTO_HIDE_MS] after the last interaction; locked ones stay until they
 * are unlocked or removed.
 */
class CursorStore {

    private val cursors = ArrayList<Cursor>()

    @Volatile
    var version: Int = 0
        private set

    @Volatile
    private var lastInteraction = 0L

    /** Cursor the user added, moved or locked most recently; drives the delta readout. */
    @Volatile
    var active: Cursor? = null
        private set

    val all: List<Cursor> get() = cursors

    val hasLocked: Boolean get() = cursors.any { it.locked }

    val isEmpty: Boolean get() = cursors.isEmpty()

    fun touch() {
        lastInteraction = SystemClock.uptimeMillis()
        version++
    }

    fun add(hz: Float, locked: Boolean = false): Cursor {
        val cursor = Cursor(hz, locked)
        cursors.add(cursor)
        active = cursor
        touch()
        return cursor
    }

    /** Marks [cursor] as the one the readouts should focus on. */
    fun markActive(cursor: Cursor) {
        active = cursor
        touch()
    }

    fun remove(cursor: Cursor) {
        if (cursors.remove(cursor)) {
            if (active === cursor) active = null
            touch()
        }
    }

    fun clear() {
        cursors.clear()
        touch()
    }

    /** The cursor closest to [hz] within [toleranceHz], or null. */
    fun nearest(hz: Float, toleranceHz: Float): Cursor? {
        var best: Cursor? = null
        var bestDistance = toleranceHz
        for (c in cursors) {
            val d = kotlin.math.abs(c.hz - hz)
            if (d <= bestDistance) {
                bestDistance = d
                best = c
            }
        }
        return best
    }

    fun toggleLock(cursor: Cursor) {
        cursor.locked = !cursor.locked
        touch()
    }

    /**
     * Cursors that should currently be drawn. Locked cursors are always visible; unlocked ones hide
     * once the user has been idle for longer than [AUTO_HIDE_MS].
     */
    fun visible(now: Long = SystemClock.uptimeMillis()): List<Cursor> {
        if (cursors.isEmpty()) return emptyList()
        if (now - lastInteraction < AUTO_HIDE_MS) return cursors
        return cursors.filter { it.locked }
    }

    /** True once the unlocked cursors have faded out. */
    fun fading(now: Long = SystemClock.uptimeMillis()): Boolean =
        cursors.isNotEmpty() && now - lastInteraction >= AUTO_HIDE_MS

    companion object {
        /** Idle time after which unlocked cursors hide. */
        const val AUTO_HIDE_MS = 5000L
    }
}

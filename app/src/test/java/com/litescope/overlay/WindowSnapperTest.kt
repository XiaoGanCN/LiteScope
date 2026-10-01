package com.litescope.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSnapperTest {

    private val screenW = 1000
    private val screenH = 800
    private val threshold = 16

    @Test
    fun snapsToANeighbourEdge() {
        val other = SnapRect(0, 100, 300, 500)
        val result = WindowSnapper.snap(
            x = 304, y = 620, w = 300, h = 150,
            others = listOf(other), screenW = screenW, screenH = screenH,
            threshold = threshold, matchSize = false
        )
        assertEquals(300, result.x)
        assertTrue(result.snappedX)
        assertFalse(result.snappedY)
    }

    @Test
    fun snappingIsOffBeyondTheThreshold() {
        val other = SnapRect(0, 100, 300, 500)
        val result = WindowSnapper.snap(
            x = 400, y = 600, w = 300, h = 150,
            others = listOf(other), screenW = screenW, screenH = screenH,
            threshold = threshold, matchSize = true
        )
        assertEquals(400, result.x)
        assertEquals(600, result.y)
        assertFalse(result.snappedX)
        assertFalse(result.snappedY)
    }

    @Test
    fun snapsToTheScreenCentreAndEdges() {
        val centred = WindowSnapper.snap(
            x = 345, y = 300, w = 300, h = 200,
            others = emptyList(), screenW = screenW, screenH = screenH,
            threshold = threshold, matchSize = true
        )
        assertEquals((screenW - 300) / 2, centred.x)
        assertTrue(centred.snappedX)

        val leftEdge = WindowSnapper.snap(
            x = 6, y = 300, w = 300, h = 200,
            others = emptyList(), screenW = screenW, screenH = screenH,
            threshold = threshold, matchSize = true
        )
        assertEquals(0, leftEdge.x)
        assertTrue(leftEdge.snappedX)
    }

    @Test
    fun dockingBesideAnotherWindowMatchesItsHeight() {
        val other = SnapRect(0, 100, 300, 500) // 300 x 400
        val result = WindowSnapper.snap(
            x = 304, y = 110, w = 300, h = 380,
            others = listOf(other), screenW = screenW, screenH = screenH,
            threshold = threshold, matchSize = true
        )
        assertEquals(300, result.x)
        assertEquals(100, result.y)
        assertEquals(400, result.h)
        assertTrue(result.snappedX)
        assertTrue(result.snappedY)
    }

    @Test
    fun matchSizeCanBeDisabled() {
        val other = SnapRect(0, 100, 300, 500)
        val result = WindowSnapper.snap(
            x = 304, y = 110, w = 300, h = 380,
            others = listOf(other), screenW = screenW, screenH = screenH,
            threshold = threshold, matchSize = false
        )
        assertEquals(300, result.x)
        assertEquals(380, result.h)
        assertEquals(110, result.y)
    }

    @Test
    fun zeroThresholdDisablesSnapping() {
        val result = WindowSnapper.snap(
            x = 5, y = 5, w = 300, h = 200,
            others = listOf(SnapRect(0, 0, 300, 200)), screenW = screenW, screenH = screenH,
            threshold = 0, matchSize = true
        )
        assertEquals(5, result.x)
        assertEquals(5, result.y)
        assertFalse(result.snappedX)
    }

    @Test
    fun smallWindowsMatchTheNeighbourWidthWhenStacked() {
        val other = SnapRect(100, 0, 600, 300) // 500 wide
        // A taller screen keeps the screen-centre candidate far away so the dock wins.
        val result = WindowSnapper.snap(
            x = 110, y = 306, w = 480, h = 200,
            others = listOf(other), screenW = screenW, screenH = 1200,
            threshold = threshold, matchSize = true
        )
        assertEquals(300, result.y)
        assertTrue(result.snappedY)
        assertEquals(500, result.w)
        assertEquals(100, result.x)
    }
}

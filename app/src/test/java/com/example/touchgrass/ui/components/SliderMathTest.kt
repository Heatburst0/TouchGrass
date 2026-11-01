package com.example.touchgrass.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderMathTest {

    private val pages = 0..500

    @Test
    fun `a saved value outside the range stretches the track instead of being clamped`() {
        assertEquals(0..2000, SliderMath.track(pages, 2000))
        assertEquals(pages, SliderMath.track(pages, 120))
    }

    @Test
    fun `locked bounds narrow where the value may go`() {
        // Weekly-plan lock on pages: can only go up from 120.
        assertEquals(120..500, SliderMath.allowed(pages, lockedMin = 120, lockedMax = null))
        // Shorts lock: can only go down from 10.
        assertEquals(1..10, SliderMath.allowed(1..50, lockedMin = null, lockedMax = 10))
    }

    @Test
    fun `dragging snaps to the step grid and stops at a locked bound`() {
        val allowed = SliderMath.allowed(pages, lockedMin = 120, lockedMax = null)
        assertEquals(255, SliderMath.snap(253.4f, pages, 5, allowed))
        assertEquals(120, SliderMath.snap(40f, pages, 5, allowed))
        // 15-minute quota grid.
        assertEquals(90, SliderMath.snap(97f, 0..2400, 15, 0..2400))
    }

    @Test
    fun `fine buttons move one step and land an off-grid value on the grid`() {
        assertEquals(35, SliderMath.stepDown(37, pages, 5, pages))
        assertEquals(40, SliderMath.stepUp(37, pages, 5, pages))
        assertEquals(30, SliderMath.stepDown(35, pages, 5, pages))
        assertEquals(0, SliderMath.stepDown(0, pages, 5, pages))
        assertEquals(10, SliderMath.stepUp(10, 1..50, 1, 1..10))
    }
}

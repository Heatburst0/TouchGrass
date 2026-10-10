package com.example.touchgrass.features.focus.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTimeCalculatorTest {
    @Test
    fun singleApp() {
        val result = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 1_000),
            event("app", AppUsageEvent.Type.BACKGROUND, 4_500),
            start = 0,
            end = 10_000
        )

        assertEquals(mapOf("app" to 3), result)
    }

    @Test
    fun switchingAppsClosesThePreviousApp() {
        val result = compute(
            event("one", AppUsageEvent.Type.FOREGROUND, 0),
            event("two", AppUsageEvent.Type.FOREGROUND, 2_000),
            event("two", AppUsageEvent.Type.BACKGROUND, 5_000),
            start = 0,
            end = 8_000
        )

        assertEquals(mapOf("one" to 2, "two" to 3), result)
    }

    @Test
    fun backgroundThenForegroundLeavesGap() {
        val result = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 0),
            event("app", AppUsageEvent.Type.BACKGROUND, 2_000),
            event("app", AppUsageEvent.Type.FOREGROUND, 5_000),
            event("app", AppUsageEvent.Type.BACKGROUND, 7_000),
            start = 0,
            end = 10_000
        )

        assertEquals(mapOf("app" to 4), result)
    }

    @Test
    fun screenOffStopsTheClock() {
        val result = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 0),
            event("", AppUsageEvent.Type.SCREEN_OFF, 2_500),
            start = 0,
            end = 10_000
        )

        assertEquals(mapOf("app" to 2), result)
    }

    @Test
    fun foregroundBeforeWindowIsClippedToStart() {
        val result = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 1_000),
            event("app", AppUsageEvent.Type.BACKGROUND, 7_000),
            start = 5_000,
            end = 10_000
        )

        assertEquals(mapOf("app" to 2), result)
    }

    @Test
    fun openAppAndEventsAfterWindowAreClippedAtEnd() {
        val openResult = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 2_000),
            start = 0,
            end = 6_000
        )
        val lateBackgroundResult = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 2_000),
            event("app", AppUsageEvent.Type.BACKGROUND, 20_000),
            start = 0,
            end = 6_000
        )

        assertEquals(mapOf("app" to 4), openResult)
        assertEquals(mapOf("app" to 4), lateBackgroundResult)
    }

    @Test
    fun unsortedInputIsProcessedChronologically() {
        val result = compute(
            event("app", AppUsageEvent.Type.BACKGROUND, 4_000),
            event("app", AppUsageEvent.Type.FOREGROUND, 1_000),
            start = 0,
            end = 10_000
        )

        assertEquals(mapOf("app" to 3), result)
    }

    @Test
    fun repeatedForegroundDoesNotResetStartTime() {
        val result = compute(
            event("app", AppUsageEvent.Type.FOREGROUND, 1_000),
            event("app", AppUsageEvent.Type.FOREGROUND, 3_000),
            event("app", AppUsageEvent.Type.BACKGROUND, 6_000),
            start = 0,
            end = 10_000
        )

        assertEquals(mapOf("app" to 5), result)
    }

    @Test
    fun emptyEventsAndInvertedWindowAreEmpty() {
        assertTrue(ForegroundTimeCalculator.compute(emptyList(), 0, 10_000).isEmpty())
        assertTrue(
            ForegroundTimeCalculator.compute(
                listOf(event("app", AppUsageEvent.Type.FOREGROUND, 0)),
                10_000,
                1_000
            ).isEmpty()
        )
    }

    private fun compute(
        vararg events: AppUsageEvent,
        start: Long,
        end: Long
    ): Map<String, Int> = ForegroundTimeCalculator.compute(events.toList(), start, end)

    private fun event(
        packageName: String,
        type: AppUsageEvent.Type,
        timeMillis: Long
    ): AppUsageEvent = AppUsageEvent(packageName, type, timeMillis)
}

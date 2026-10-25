package com.example.touchgrass.features.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FocusScheduleTest {

    private fun schedule(
        hour: Int = 9,
        minute: Int = 0,
        zoneId: String = "UTC",
        days: Set<java.time.DayOfWeek>? = null,
        enabled: Boolean = true
    ) = FocusSchedule(
        id = "s1", title = "Focus", days = days, hour = hour, minute = minute,
        focusBlockMin = 25, breakMin = 5, cycles = 4, blockedPackages = emptySet(),
        enabled = enabled, zoneId = zoneId
    )

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `later today in the schedule's own zone`() {
        // 07:30 in Kolkata → today's 09:00 IST is 03:30Z.
        val next = schedule(zoneId = "Asia/Kolkata").nextRunAt(at("2026-10-25T02:00:00Z"))

        assertEquals(at("2026-10-25T03:30:00Z"), next)
    }

    @Test
    fun `time already passed rolls to tomorrow`() {
        val next = schedule(zoneId = "Asia/Kolkata").nextRunAt(at("2026-10-25T04:00:00Z"))

        assertEquals(at("2026-10-26T03:30:00Z"), next)
    }

    @Test
    fun `the stored zone changes the instant`() {
        val now = at("2026-10-25T00:00:00Z")

        assertEquals(at("2026-10-25T09:00:00Z"), schedule(zoneId = "UTC").nextRunAt(now))
        assertEquals(at("2026-10-25T03:30:00Z"), schedule(zoneId = "Asia/Kolkata").nextRunAt(now))
    }

    @Test
    fun `weekday schedule skips the weekend`() {
        // 2026-10-24 is a Saturday.
        val next = schedule(days = FocusSchedule.WEEKDAYS).nextRunAt(at("2026-10-24T10:00:00Z"))

        assertEquals(at("2026-10-26T09:00:00Z"), next)
    }

    @Test
    fun `next run is derived from now, not the wall clock`() {
        val next = schedule().nextRunAt(at("2020-01-01T00:00:00Z"))

        assertEquals(at("2020-01-01T09:00:00Z"), next)
    }

    @Test
    fun `disabled schedule never runs`() {
        assertNull(schedule(enabled = false).nextRunAt(at("2026-10-25T00:00:00Z")))
    }

    @Test
    fun `an invalid zone falls back instead of crashing`() {
        val now = at("2026-10-25T00:00:00Z")
        val next = schedule(zoneId = "Not/AZone").nextRunAt(now)

        assertNotNull(next)
        assertTrue(checkNotNull(next) > now)
    }

    @Test
    fun `total minutes include breaks between cycles only`() {
        assertEquals(4 * 25 + 3 * 5, schedule().totalMinutes)
    }
}

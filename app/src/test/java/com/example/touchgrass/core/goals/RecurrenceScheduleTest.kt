package com.example.touchgrass.core.goals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RecurrenceScheduleTest {

    private val utc = ZoneOffset.UTC
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `encode then decode round-trips every recurrence`() {
        listOf(
            Recurrence.Once,
            Recurrence.Daily,
            Recurrence.Weekly,
            Recurrence.Custom(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        ).forEach { r ->
            assertEquals(r, RecurrenceSchedule.decode(RecurrenceSchedule.encode(r)))
        }
    }

    @Test
    fun `missing or unknown recurrence decodes as once`() {
        assertEquals(Recurrence.Once, RecurrenceSchedule.decode(null))
        assertEquals(Recurrence.Once, RecurrenceSchedule.decode(JSONObject().put("kind", "YEARLY")))
    }

    @Test
    fun `daily period spans the calendar day`() {
        val p = checkNotNull(RecurrenceSchedule.periodOn(Recurrence.Daily, LocalDate.of(2026, 10, 25), utc))

        assertEquals("2026-10-25", p.key)
        assertEquals(millis("2026-10-25T00:00:00Z"), p.startAt)
        assertEquals(millis("2026-10-26T00:00:00Z"), p.endAt)
    }

    @Test
    fun `weekly period runs Monday to Monday with an ISO week key`() {
        // Sunday 2026-10-25 belongs to the week starting Monday 2026-10-19 (ISO week 43).
        val p = checkNotNull(RecurrenceSchedule.periodOn(Recurrence.Weekly, LocalDate.of(2026, 10, 25), utc))

        assertEquals("2026-W43", p.key)
        assertEquals(millis("2026-10-19T00:00:00Z"), p.startAt)
        assertEquals(millis("2026-10-26T00:00:00Z"), p.endAt)
    }

    @Test
    fun `custom recurrence is dormant on off days`() {
        val mondays = Recurrence.Custom(setOf(DayOfWeek.MONDAY))

        assertNull(RecurrenceSchedule.periodOn(mondays, LocalDate.of(2026, 10, 25), utc))
    }

    @Test
    fun `period at or after skips dormant days`() {
        val mondays = Recurrence.Custom(setOf(DayOfWeek.MONDAY))
        val p = checkNotNull(RecurrenceSchedule.periodAtOrAfter(mondays, LocalDate.of(2026, 10, 25), utc))

        assertEquals("2026-10-26", p.key)
    }

    @Test
    fun `once has no periods`() {
        assertNull(RecurrenceSchedule.periodAtOrAfter(Recurrence.Once, LocalDate.of(2026, 10, 25), utc))
    }
}

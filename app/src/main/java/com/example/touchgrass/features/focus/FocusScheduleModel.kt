package com.example.touchgrass.features.focus

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * A recurring focus session the user scheduled. The server owns the definition
 * (stored in Supabase `focus_schedules`); each device computes its own next run
 * from [days] + [hour]/[minute] and self-triggers, so it works offline.
 *
 * [days] == null means every day; otherwise it fires only on those weekdays
 * (so "Mon–Fri" is the weekday set) — same shape as the reading/GitHub recurrence.
 */
data class FocusSchedule(
    val id: String,
    val title: String,
    val days: Set<DayOfWeek>?,
    val hour: Int,
    val minute: Int,
    val focusBlockMin: Int,
    val breakMin: Int,
    val cycles: Int,
    val blockedPackages: Set<String>,
    val reminders: Set<Int> = emptySet(),   // minutes-before to notify (e.g. 60, 5)
    val enabled: Boolean
) {
    /** Whole session length including breaks. */
    val totalMinutes: Int get() = cycles * focusBlockMin + (cycles - 1).coerceAtLeast(0) * breakMin

    fun toConfig(): FocusConfig = FocusConfig(
        focusBlockMin = focusBlockMin,
        breakMin = FocusConfig.capBreak(focusBlockMin, breakMin),
        cycles = cycles,
        blockedPackages = blockedPackages,
        strict = false
    )

    /** Epoch millis of the next fire strictly after [now], or null if disabled. */
    fun nextRunAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!enabled) return null
        val today = LocalDate.now(zone)
        for (i in 0..8L) {
            val date = today.plusDays(i)
            val runs = days == null || date.dayOfWeek in days
            if (!runs) continue
            val at = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (at > now) return at
        }
        return null
    }

    val timeLabel: String get() = "%02d:%02d".format(hour, minute)
    val daysLabel: String
        get() = when {
            days == null -> "Every day"
            days.size == 7 -> "Every day"
            days == WEEKDAYS -> "Weekdays"
            else -> days.sorted().joinToString(" ") { it.name.take(3) }
        }

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )
    }
}

package com.example.touchgrass.core.goals

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** How often a Task goal resets its progress and settles a reward/penalty. */
sealed interface Recurrence {
    data object Once : Recurrence
    data object Daily : Recurrence
    data object Weekly : Recurrence
    data class Custom(val days: Set<DayOfWeek>) : Recurrence
}

/** One active period: a stable key + the instant it ends (exclusive, epoch millis). */
data class Period(val key: String, val endAt: Long)

object RecurrenceSchedule {

    fun encode(r: Recurrence): JSONObject = when (r) {
        Recurrence.Once -> JSONObject().put("kind", "ONCE")
        Recurrence.Daily -> JSONObject().put("kind", "DAILY")
        Recurrence.Weekly -> JSONObject().put("kind", "WEEKLY")
        is Recurrence.Custom -> JSONObject().put("kind", "CUSTOM")
            .put("days", JSONArray(r.days.map { it.value }))
    }

    fun decode(o: JSONObject?): Recurrence {
        if (o == null) return Recurrence.Once
        return when (o.optString("kind", "ONCE")) {
            "DAILY" -> Recurrence.Daily
            "WEEKLY" -> Recurrence.Weekly
            "CUSTOM" -> {
                val arr = o.optJSONArray("days") ?: JSONArray()
                Recurrence.Custom((0 until arr.length()).map { DayOfWeek.of(arr.getInt(it)) }.toSet())
            }
            else -> Recurrence.Once
        }
    }

    private fun startOfDay(date: LocalDate, zone: ZoneId) =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The active period covering [date], or null when dormant that day (Custom
     *  off-day, or Once which has no periods). */
    fun periodOn(r: Recurrence, date: LocalDate, zone: ZoneId): Period? = when (r) {
        Recurrence.Once -> null
        Recurrence.Daily -> Period(date.toString(), startOfDay(date.plusDays(1), zone))
        Recurrence.Weekly -> {
            val monday = date.with(DayOfWeek.MONDAY)
            val y = monday.get(IsoFields.WEEK_BASED_YEAR)
            val w = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            Period("%d-W%02d".format(y, w), startOfDay(monday.plusDays(7), zone))
        }
        is Recurrence.Custom ->
            if (date.dayOfWeek in r.days) Period(date.toString(), startOfDay(date.plusDays(1), zone))
            else null
    }

    /** First active period at or after [date] — used to skip dormant days. */
    fun periodAtOrAfter(r: Recurrence, date: LocalDate, zone: ZoneId): Period? {
        if (r == Recurrence.Once) return null
        var d = date
        repeat(370) {
            periodOn(r, d, zone)?.let { return it }
            d = d.plusDays(1)
        }
        return null
    }
}

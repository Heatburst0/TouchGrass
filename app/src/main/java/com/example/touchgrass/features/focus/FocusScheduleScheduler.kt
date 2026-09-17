package com.example.touchgrass.features.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.touchgrass.core.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms an AlarmManager alarm for the soonest recurring focus session. The chosen
 * session ({fireAt, config}) is persisted so [FocusScheduleReceiver] can start it
 * even after a cold start, and [rearmFromPending] re-sets the alarm after a reboot.
 */
@Singleton
class FocusScheduleScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Recompute the soonest schedule, persist it, and arm the alarm + reminders. */
    suspend fun reschedule(schedules: List<FocusSchedule>) {
        val now = System.currentTimeMillis()
        cancelReminders()
        val next = schedules
            .mapNotNull { s -> s.nextRunAt(now)?.let { it to s } }
            .minByOrNull { it.first }
        if (next == null) {
            settings.setScheduledPending("")
            cancelAlarm()
            return
        }
        val (fireAt, schedule) = next
        settings.setScheduledPending(pendingJson(fireAt, schedule))
        setAlarm(fireAt)
        armReminders(fireAt, schedule, now)
    }

    private fun armReminders(fireAt: Long, schedule: FocusSchedule, now: Long) {
        schedule.reminders.forEach { minutes ->
            val at = fireAt - minutes * 60_000L
            if (at <= now) return@forEach
            val pi = reminderPendingIntent(minutes, schedule.title, fireAt)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (exact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelReminders() {
        REMINDER_OFFSETS.forEach { minutes ->
            alarmManager.cancel(reminderPendingIntent(minutes, "", 0L))
        }
    }

    private fun reminderPendingIntent(minutes: Int, title: String, fireAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, REMINDER_REQUEST_BASE + minutes,
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_START_AT, fireAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** After reboot / process death, re-arm from the persisted pending. */
    suspend fun rearmFromPending() {
        val fireAt = parseFireAt(settings.getScheduledPending()) ?: return
        if (fireAt > System.currentTimeMillis()) setAlarm(fireAt)
    }

    private fun setAlarm(atEpochMillis: Long) {
        val pi = pendingIntent()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pi)
        }
    }

    private fun cancelAlarm() = alarmManager.cancel(pendingIntent())

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, FocusScheduleReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val REQUEST_CODE = 7302
        private const val REMINDER_REQUEST_BASE = 7400
        /** Reminder offsets (minutes) the UI can toggle; also the set we cancel. */
        val REMINDER_OFFSETS = listOf(60, 15, 5)
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_AT = "startAt"

        fun pendingJson(fireAt: Long, s: FocusSchedule): String = JSONObject()
            .put("fireAt", fireAt)
            .put("focusBlockMin", s.focusBlockMin)
            .put("breakMin", FocusConfig.capBreak(s.focusBlockMin, s.breakMin))
            .put("cycles", s.cycles)
            .put("blocked", JSONArray(s.blockedPackages.toList()))
            .toString()

        fun parseFireAt(json: String): Long? =
            if (json.isBlank()) null else runCatching { JSONObject(json).getLong("fireAt") }.getOrNull()

        /** Parse the persisted pending into (fireAt, config). */
        fun parsePending(json: String): Pair<Long, FocusConfig>? {
            if (json.isBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                val arr = o.optJSONArray("blocked") ?: JSONArray()
                val blocked = (0 until arr.length()).map { arr.getString(it) }.toSet()
                o.getLong("fireAt") to FocusConfig(
                    focusBlockMin = o.getInt("focusBlockMin"),
                    breakMin = o.getInt("breakMin"),
                    cycles = o.getInt("cycles"),
                    blockedPackages = blocked,
                    strict = false
                )
            }.getOrNull()
        }
    }
}

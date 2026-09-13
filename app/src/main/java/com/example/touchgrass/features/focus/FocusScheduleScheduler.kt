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

    /** Recompute the soonest schedule, persist it, and arm the alarm. */
    suspend fun reschedule(schedules: List<FocusSchedule>) {
        val now = System.currentTimeMillis()
        val next = schedules
            .mapNotNull { s -> s.nextRunAt(now)?.let { it to s } }
            .minByOrNull { it.first }
        if (next == null) {
            settings.setScheduledPending("")
            cancelAlarm()
            return
        }
        settings.setScheduledPending(pendingJson(next.first, next.second))
        setAlarm(next.first)
    }

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

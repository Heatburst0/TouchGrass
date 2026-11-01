package com.example.touchgrass.features.worktargets

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Something the user wants to spend time in on their laptop: an app, optionally one
 * project inside it. Its weekly quota lives in the weekly plan (keyed by [id]); the
 * auto-focus settings are read by the laptop agent.
 */
data class WorkTarget(
    val id: String,
    val label: String,
    val appName: String,           // lowercased substring of the laptop app (device_apps.app_name)
    val project: String?,          // null = any project
    val autoFocus: Boolean = false,
    val autoFocusAfterMin: Int = DEFAULT_AFTER_MIN,
    val focusMin: Int = DEFAULT_FOCUS_MIN,
    val blockSites: Boolean = true,
    val blockPhone: Boolean = true,
    val enabled: Boolean = true
) {
    companion object {
        const val DEFAULT_AFTER_MIN = 3
        const val DEFAULT_FOCUS_MIN = 50
        val AFTER_MIN_RANGE = 1..60
        val FOCUS_MIN_RANGE = 5..240
    }
}

/** One app_usage row: active seconds on one laptop, local day, app and project. */
data class AppUsageRow(
    val day: LocalDate,
    val appName: String,
    val project: String,   // "" when the agent couldn't tell
    val activeSeconds: Int
)

/** Pure quota math over app_usage rows. */
object WorkTargetUsage {

    /** Same rule as the agent: app substring + (any project, or same project ignoring case). */
    fun matches(target: WorkTarget, appName: String, project: String): Boolean {
        if (target.appName.isBlank() || !appName.contains(target.appName, ignoreCase = true)) return false
        val wanted = target.project?.trim().orEmpty()
        return wanted.isEmpty() || project.trim().equals(wanted, ignoreCase = true)
    }

    /** Quota weeks run Monday–Sunday, like the weekly plan and weekly goals. */
    fun weekStart(today: LocalDate): LocalDate = today.with(DayOfWeek.MONDAY)

    /** Whole minutes spent on [target] in the week starting [weekStart] (all laptops). */
    fun minutesInWeek(target: WorkTarget, rows: List<AppUsageRow>, weekStart: LocalDate): Int {
        val end = weekStart.plusDays(7)
        val seconds = rows.asSequence()
            .filter { !it.day.isBefore(weekStart) && it.day.isBefore(end) }
            .filter { matches(target, it.appName, it.project) }
            .sumOf { it.activeSeconds.toLong() }
        return (seconds / 60).toInt()
    }

    /** Projects seen in [appName] (or any app when null), most-used first. */
    fun recentProjects(rows: List<AppUsageRow>, appName: String?): List<String> =
        rows.asSequence()
            .filter { it.project.isNotBlank() }
            .filter { appName == null || it.appName.contains(appName, ignoreCase = true) }
            .groupBy { it.project }
            .mapValues { (_, r) -> r.sumOf { it.activeSeconds.toLong() } }
            .entries.sortedByDescending { it.value }
            .map { it.key }

    /** "3h 20m", "45m", "0m". */
    fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

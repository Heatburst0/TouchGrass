package com.example.touchgrass.features.focus.insights

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Android usage-stats adapter for focus insights. */
@Singleton
class UsageEventsSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Whether the user granted usage access. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Per-package foreground seconds during [startMillis, endMillis]. */
    suspend fun foregroundSeconds(startMillis: Long, endMillis: Long): Map<String, Int> =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess()) return@withContext emptyMap()
            runUsageQuery {
                val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val queryStart = if (startMillis < Long.MIN_VALUE + LOOK_BACK_MS) {
                    Long.MIN_VALUE
                } else {
                    startMillis - LOOK_BACK_MS
                }
                val usageEvents = manager.queryEvents(queryStart, endMillis)
                val event = UsageEvents.Event()
                val mappedEvents = mutableListOf<AppUsageEvent>()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    event.toAppUsageEvent()?.let(mappedEvents::add)
                }
                val excluded = excludedPackages() // one PackageManager query, not one per app
                ForegroundTimeCalculator.compute(mappedEvents, startMillis, endMillis)
                    .filterKeys { it !in excluded }
            } ?: emptyMap()
        }

    /** Total foreground millis per package since [sinceMillis] until now. */
    suspend fun totalForegroundMillis(sinceMillis: Long): Map<String, Long> =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess()) return@withContext emptyMap()
            runUsageQuery {
                val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val excluded = excludedPackages()
                manager.queryAndAggregateUsageStats(sinceMillis, System.currentTimeMillis())
                    .mapNotNull { (packageName, stats) ->
                        val millis = stats.totalTimeInForeground
                        if (packageName !in excluded && millis > 0L) packageName to millis else null
                    }
                    .toMap()
            } ?: emptyMap()
        }

    private fun UsageEvents.Event.toAppUsageEvent(): AppUsageEvent? {
        val type = when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> AppUsageEvent.Type.FOREGROUND
            UsageEvents.Event.ACTIVITY_PAUSED -> AppUsageEvent.Type.BACKGROUND
            UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.KEYGUARD_SHOWN -> AppUsageEvent.Type.SCREEN_OFF
            else -> return null
        }
        return AppUsageEvent(packageName.orEmpty(), type, timeStamp)
    }

    private fun excludedPackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager.queryIntentActivities(homeIntent, 0)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
        return launchers + context.packageName + SYSTEM_UI_PACKAGE
    }

    private inline fun <T> runUsageQuery(query: () -> T): T? = try {
        query()
    } catch (error: Exception) {
        Timber.tag(LOG_TAG).w(error, "Usage stats query failed")
        null
    }

    private companion object {
        const val LOOK_BACK_MS = 3L * 60L * 60L * 1_000L
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val LOG_TAG = "Usage"
    }
}

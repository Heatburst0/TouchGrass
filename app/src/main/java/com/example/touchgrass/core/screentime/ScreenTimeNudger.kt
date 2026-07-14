package com.example.touchgrass.core.screentime

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.touchgrass.MainActivity
import com.example.touchgrass.R
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.di.ApplicationScope
import com.example.touchgrass.formatDuration
import com.example.touchgrass.hasUsageAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches cumulative watch-time (YouTube, Instagram, Netflix) via UsageStatsManager
 * and fires a "go read a page" notification every time another
 * [NUDGE_EVERY_MS] of it piles up. Cheap to call — it self-throttles — so callers
 * can invoke it opportunistically (accessibility events, app resume).
 */
@Singleton
class ScreenTimeNudger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile
    private var lastCheckAt = 0L

    fun maybeNudge() {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        if (!hasUsageAccess(context)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        scope.launch {
            try {
                val watched = todayWatchTimeMillis()
                val bucket = (watched / NUDGE_EVERY_MS).toInt()
                if (bucket <= 0) return@launch

                val today = LocalDate.now().toString()
                if (settings.getNudgeBucket(today) >= bucket) return@launch
                settings.setNudgeBucket(today, bucket)
                postNudge(watched)
            } catch (e: Exception) {
                Timber.tag("ScreenTime").e(e, "Nudge check failed")
            }
        }
    }

    /** Total foreground time today across the watched entertainment apps. */
    fun todayWatchTimeMillis(): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = usm.queryAndAggregateUsageStats(startOfDay, System.currentTimeMillis())
        return WATCHED_PACKAGES.sumOf { stats[it]?.totalTimeInForeground ?: 0L }
    }

    private fun postNudge(watchedMillis: Long) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_START_ROUTE, "library")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time for one page")
            .setContentText("${formatDuration(watchedMillis)} of watching today. Balance it with a page — it earns points too.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Timber.tag("ScreenTime").i("Reading nudge posted (%s watched)", formatDuration(watchedMillis))
        } catch (e: SecurityException) {
            Timber.tag("ScreenTime").w(e, "Notification permission missing")
        }
    }

    companion object {
        const val CHANNEL_ID = "reading_nudges"
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val NUDGE_EVERY_MS = 3 * 60 * 60 * 1000L // every 3h of watch time

        val WATCHED_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.netflix.mediaclient"
        )
    }
}

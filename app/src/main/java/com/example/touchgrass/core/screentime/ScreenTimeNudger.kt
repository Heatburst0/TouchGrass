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
import com.example.touchgrass.core.data.db.BookDao
import com.example.touchgrass.di.ApplicationScope
import com.example.touchgrass.formatDuration
import com.example.touchgrass.hasUsageAccess
import com.example.touchgrass.presentation.gate.ReadingGateActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    private val bookDao: BookDao,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile
    private var lastCheckAt = 0L

    @Volatile
    private var lastGateLaunchAt = 0L

    /** Hot mirror of the persisted gate flag, cheap to read per accessibility event. */
    private val gatePending: StateFlow<Boolean> = settings.readingGatePending
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun maybeNudge() {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        if (!hasUsageAccess(context)) return

        scope.launch {
            try {
                val watched = todayWatchTimeMillis()
                val bucket = (watched / NUDGE_EVERY_MS).toInt()
                if (bucket <= 0) return@launch

                val today = LocalDate.now().toString()
                if (settings.getNudgeBucket(today) >= bucket) return@launch
                settings.setNudgeBucket(today, bucket)

                // Force-read mode: take over the screen with the user's PDF
                // instead of politely notifying.
                val gateBook =
                    if (settings.readingGateEnabled.first()) bookDao.latestPdfBook() else null
                if (gateBook != null) {
                    settings.setReadingGatePending(true)
                    launchGate(gateBook.id)
                    Timber.tag("ScreenTime").i("Reading gate armed (%s watched)", formatDuration(watched))
                } else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    postNudge(watched)
                }
            } catch (e: Exception) {
                Timber.tag("ScreenTime").e(e, "Nudge check failed")
            }
        }
    }

    /**
     * Called on every accessibility event from a watched app. While a gate is
     * owed, keeps shoving the reader back on screen so Netflix/Shorts/Reels
     * can't continue until one page is quiz-verified.
     */
    fun enforceGate() {
        if (!gatePending.value) return
        val now = System.currentTimeMillis()
        if (now - lastGateLaunchAt < GATE_RELAUNCH_MS) return
        lastGateLaunchAt = now
        scope.launch {
            val book = bookDao.latestPdfBook()
            if (book == null) {
                // Book got deleted — don't brick the phone over it
                settings.setReadingGatePending(false)
                return@launch
            }
            launchGate(book.id)
        }
    }

    private fun launchGate(bookId: Long) {
        try {
            context.startActivity(
                Intent(context, ReadingGateActivity::class.java).apply {
                    putExtra("bookId", bookId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Timber.tag("ScreenTime").e(e, "Could not launch reading gate")
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
        private const val GATE_RELAUNCH_MS = 4_000L // re-shove cadence while gated

        val WATCHED_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.netflix.mediaclient"
        )
    }
}

package com.example.touchgrass.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.touchgrass.MainActivity
import com.example.touchgrass.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App notification channels. Add one here and it is created at startup; nothing
 * else to wire. Kept coarse on purpose so the user has a small, meaningful set of
 * toggles in system settings.
 */
enum class NotifChannel(
    val id: String,
    val channelTitle: String,
    val importance: Int,
    val channelDescription: String
) {
    GOALS("goals", "Goals & streaks", NotificationManager.IMPORTANCE_DEFAULT,
        "Commits logged, streaks, and goals you've met"),
    REMINDERS("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH,
        "Nudges to keep tracking running")
}

/**
 * The single door for posting notifications. Owns channel creation and the
 * POST_NOTIFICATIONS permission gate, so callers just say what to show. A new use
 * case = pick a channel + a stable id from [Ids]; no boilerplate per feature.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init { createChannels() }

    fun post(
        channel: NotifChannel,
        id: Int,
        title: String,
        body: String,
        ongoing: Boolean = false
    ) {
        if (!hasPermission()) return
        val tap = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOngoing(ongoing)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /**
     * A live-countdown notification for a running focus session. Android ticks the
     * chronometer itself (no per-second updates from us). When [strict] the session
     * is a commitment: the notification is ongoing and non-dismissible.
     */
    fun postFocusCountdown(title: String, endAt: Long, strict: Boolean) {
        if (!hasPermission()) return
        val tap = PendingIntent.getActivity(
            context, Ids.FOCUS_ONGOING,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, NotifChannel.GOALS.id)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setWhen(endAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(strict)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(Ids.FOCUS_ONGOING, notification)
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** POST_NOTIFICATIONS is runtime-granted on Android 13+; below that it's implicit. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        NotifChannel.values().forEach { ch ->
            nm.createNotificationChannel(
                NotificationChannel(ch.id, ch.channelTitle, ch.importance).apply {
                    description = ch.channelDescription
                }
            )
        }
    }

    /** Stable ids so updates/cancels target the right notification. */
    object Ids {
        const val ACCESSIBILITY_OFF = 1001
        const val FOCUS_ONGOING = 1002
        const val FOCUS_DONE = 1003
        fun commit(goalId: Long): Int = (2000L + goalId).toInt()
    }
}

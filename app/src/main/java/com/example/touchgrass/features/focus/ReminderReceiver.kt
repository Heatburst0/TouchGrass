package com.example.touchgrass.features.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.touchgrass.core.notifications.NotifChannel
import com.example.touchgrass.core.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Posts a heads-up notification a set time before a scheduled focus session. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notifier: Notifier

    override fun onReceive(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra(FocusScheduleScheduler.EXTRA_MINUTES, 0)
        val title = intent.getStringExtra(FocusScheduleScheduler.EXTRA_TITLE)?.ifBlank { "Focus" } ?: "Focus"
        notifier.post(
            channel = NotifChannel.REMINDERS,
            id = Notifier.Ids.FOCUS_REMINDER + minutes,
            title = "Focus session soon",
            body = "\"$title\" starts in ${humanize(minutes)}. Get ready."
        )
    }

    private fun humanize(minutes: Int): String = when {
        minutes >= 120 && minutes % 60 == 0 -> "${minutes / 60} hours"
        minutes == 60 -> "1 hour"
        minutes > 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "$minutes minutes"
    }
}

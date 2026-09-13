package com.example.touchgrass.features.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.touchgrass.core.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires at a scheduled focus session's time. Starts the session (unless one is
 * already running) from the persisted pending config, then arms the next run from
 * the offline schedule cache — so recurring sessions keep going without the app open.
 */
@AndroidEntryPoint
class FocusScheduleReceiver : BroadcastReceiver() {

    @Inject
    lateinit var focusSessionManager: FocusSessionManager

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var scheduler: FocusScheduleScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val parsed = FocusScheduleScheduler.parsePending(settings.getScheduledPending())
                val now = System.currentTimeMillis()
                if (parsed != null && parsed.first <= now + TOLERANCE_MS) {
                    focusSessionManager.startScheduled(parsed.second)
                }
                val schedules = FocusScheduleRepository.parseCache(settings.getScheduleCacheJson())
                scheduler.reschedule(schedules.filter { it.enabled })
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TOLERANCE_MS = 120_000L
    }
}

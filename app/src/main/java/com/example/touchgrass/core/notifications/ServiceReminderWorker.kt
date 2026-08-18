package com.example.touchgrass.core.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.touchgrass.isAccessibilityEnabled
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Routine check: if the accessibility service is off, remind the user to turn it
 * back on (it silently dies on some OEM updates). Clears the reminder once it's on
 * again. Scheduled periodically from [com.example.touchgrass.TouchGrassApp].
 */
@HiltWorker
class ServiceReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notifier: Notifier
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (isAccessibilityEnabled(applicationContext)) {
            notifier.cancel(Notifier.Ids.ACCESSIBILITY_OFF)
        } else {
            notifier.post(
                channel = NotifChannel.REMINDERS,
                id = Notifier.Ids.ACCESSIBILITY_OFF,
                title = "Tracking is off",
                body = "TouchGrass isn't watching right now. Tap to turn tracking back on."
            )
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "service_reminder"
    }
}

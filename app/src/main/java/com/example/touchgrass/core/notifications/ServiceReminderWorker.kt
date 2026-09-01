package com.example.touchgrass.core.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.touchgrass.isAccessibilityEnabled
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Reminds the user, on a recurring schedule, that tracking is off — but only while
 * it actually IS off. This is NOT a periodic worker: it's a self-rescheduling
 * one-time loop, kicked off by [InspectorService] the moment the accessibility
 * service is unbound, and cancelled the moment it reconnects. So the reminder is
 * tied to the service turning off, never to opening the app.
 *
 * Each run: if the service is back on → clear the notification and stop (don't
 * reschedule). If still off → notify and schedule the next reminder.
 */
@HiltWorker
class ServiceReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notifier: Notifier
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (isAccessibilityEnabled(applicationContext)) {
            notifier.cancel(Notifier.Ids.ACCESSIBILITY_OFF)   // back on — end the loop
        } else {
            notifier.post(
                channel = NotifChannel.REMINDERS,
                id = Notifier.Ids.ACCESSIBILITY_OFF,
                title = "Tracking is off",
                body = "TouchGrass isn't watching. Tap to turn tracking back on."
            )
            reschedule(applicationContext)                    // keep reminding while off
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "service_reminder"
        private const val FIRST_DELAY_MIN = 1L    // short grace so a transient restart won't false-alarm
        private const val REPEAT_MIN = 30L        // recurring reminder cadence while off

        /** Start (or restart) the reminder loop. Idempotent. */
        fun start(context: Context) = enqueue(context, FIRST_DELAY_MIN)

        /** Stop the loop and clear any pending reminder (service is back on). */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        private fun reschedule(context: Context) = enqueue(context, REPEAT_MIN)

        private fun enqueue(context: Context, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<ServiceReminderWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

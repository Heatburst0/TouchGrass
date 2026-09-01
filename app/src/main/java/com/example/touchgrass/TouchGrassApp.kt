package com.example.touchgrass

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.work.GoalMaintenanceWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class TouchGrassApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // WorkManager picks this up automatically (on-demand init) because the
    // Application implements Configuration.Provider.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                ScreenTimeNudger.CHANNEL_ID,
                "Reading nudges",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Suggests reading a page after long watch sessions"
            }
        )

        // Retire the old GitHub-only periodic worker (its class no longer exists).
        WorkManager.getInstance(this).cancelUniqueWork("github_daily_check")
        scheduleGoalMaintenance()
    }

    /** Background maintenance (~every 3h): GitHub poll + one-shot & recurring goal
     *  settlement, so misses and streaks settle even when the app is never opened
     *  that day. KEEP = don't reset the schedule. */
    private fun scheduleGoalMaintenance() {
        val request = PeriodicWorkRequestBuilder<GoalMaintenanceWorker>(3, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            GoalMaintenanceWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

}

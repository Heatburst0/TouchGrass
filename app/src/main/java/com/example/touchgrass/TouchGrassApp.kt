package com.example.touchgrass

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class TouchGrassApp : Application() {
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
    }
}

package com.example.touchgrass

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

// ShortsTrackerApp.kt
@HiltAndroidApp
class TouchGrassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
package com.example.touchgrass.features.focus

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A user-facing app the picker can offer for the focus blocklist. */
data class AppInfo(val packageName: String, val label: String)

/** Enumerates launchable apps on the device for the focus blocklist picker. */
@Singleton
class InstalledApps @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Launchable apps (excluding this app), sorted by label. */
    suspend fun launchable(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Best-effort label for a package (falls back to the package name). */
    fun label(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

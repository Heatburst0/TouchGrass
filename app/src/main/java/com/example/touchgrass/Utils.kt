package com.example.touchgrass

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.example.touchgrass.core.service.InspectorService
import timber.log.Timber

fun isAccessibilityEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, InspectorService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServicesSetting.split(':').any {
        ComponentName.unflattenFromString(it) == expectedComponentName
    }
}

/** True when the OS has agreed not to kill us for battery savings. */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        )
    } catch (e: Exception) {
        // Some OEMs don't handle the direct-request intent; fall back to the list screen.
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/**
 * Self-heal for the accessibility toggle. Only works if WRITE_SECURE_SETTINGS was
 * granted once via adb:
 *   adb shell pm grant com.example.touchgrass android.permission.WRITE_SECURE_SETTINGS
 * With that grant, the app can flip its own service back on whenever the system
 * (or an OEM battery manager) turns it off. Without the grant this is a no-op.
 */
fun tryForceEnableAccessibility(context: Context): Boolean {
    if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        != PackageManager.PERMISSION_GRANTED
    ) return false
    if (isAccessibilityEnabled(context)) return true

    return try {
        val component = ComponentName(context, InspectorService::class.java).flattenToString()
        val existing = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val updated = if (existing.isBlank()) component else "$existing:$component"
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated
        )
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Timber.tag("ShortsTracker").i("Accessibility service re-enabled via secure settings")
        true
    } catch (e: Exception) {
        Timber.tag("ShortsTracker").e(e, "Failed to force-enable accessibility")
        false
    }
}

/** "1h 23m", "12m 5s" or "45s" */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

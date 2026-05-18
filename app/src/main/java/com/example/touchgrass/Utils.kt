package com.example.touchgrass

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.touchgrass.core.service.InspectorService // Make sure this matches your package

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
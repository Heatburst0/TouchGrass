package com.example.touchgrass.core.sync

import android.os.Build
import com.example.touchgrass.core.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This install's [DeviceInfo] for sync/registration. The id is a stable UUID kept
 * in DataStore (survives updates, distinct per install); the label is the device
 * model. The laptop agent will produce the DESKTOP equivalent.
 */
@Singleton
class DeviceIdentity @Inject constructor(
    private val settings: SettingsRepository
) {
    suspend fun current(): DeviceInfo = DeviceInfo(
        id = settings.getOrCreateDeviceId(),
        platform = DevicePlatform.ANDROID,
        name = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Android device" },
        lastSeenAt = System.currentTimeMillis()
    )
}

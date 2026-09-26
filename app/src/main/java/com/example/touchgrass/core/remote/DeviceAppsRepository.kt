package com.example.touchgrass.core.remote

import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** An app the laptop agent has seen in the foreground and reported. */
data class DeviceApp(
    val name: String,        // lowercased match key (what the policy compares)
    val displayName: String  // what the UI shows
)

@Serializable
private data class RemoteDeviceApp(
    val app_name: String = "",
    val display_name: String = ""
)

private fun RemoteDeviceApp.toDomain() =
    DeviceApp(name = app_name, displayName = display_name.ifBlank { app_name })

/**
 * Read-only view of the apps discovered on the user's laptop(s). The phone shows
 * these in the rules picker; the user's choices are written into [FocusPolicyRepository],
 * never back here — so this repository has no update().
 */
@Singleton
class DeviceAppsRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _apps = MutableStateFlow<List<DeviceApp>>(emptyList())
    val apps: StateFlow<List<DeviceApp>> = _apps.asStateFlow()

    val isConfigured: Boolean = auth.isConfigured

    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) refresh()
                }
            }
        }
    }

    suspend fun refresh() {
        runCatching {
            val rows = supabase.from("device_apps").select().decodeList<RemoteDeviceApp>()
            _apps.value = rows
                .map { it.toDomain() }
                .distinctBy { it.name }
                .sortedBy { it.displayName.lowercase() }
        }.onFailure { Timber.tag("Sync").w(it, "device apps refresh failed") }
    }
}

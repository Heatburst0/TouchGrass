package com.example.touchgrass.core.remote

import com.example.touchgrass.core.sync.DeviceIdentity
import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Row we upsert into `devices`. user_id is filled by the DB default (auth.uid()). */
@Serializable
private data class DeviceRow(
    val id: String,
    val platform: String,
    val name: String
)

/**
 * Registers this install in the `devices` table whenever the account is signed in,
 * so the backend (and other devices) know it exists. Idempotent — the stable
 * per-install id upserts one row.
 */
@Singleton
class DeviceRegistrar @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    private val deviceIdentity: DeviceIdentity,
    @ApplicationScope private val scope: CoroutineScope
) {
    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) register()
                }
            }
        }
    }

    /** Idempotent upsert of this device. Public so the sync flow can await it
     *  before pushing rows that reference device_id. */
    suspend fun register() {
        runCatching {
            val d = deviceIdentity.current()
            supabase.from("devices").upsert(DeviceRow(d.id, d.platform.name, d.name))
            Timber.tag("Sync").i("Device registered: %s", d.name)
        }.onFailure { Timber.tag("Sync").w(it, "Device register failed") }
    }
}

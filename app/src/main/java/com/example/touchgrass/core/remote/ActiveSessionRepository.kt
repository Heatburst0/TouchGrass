package com.example.touchgrass.core.remote

import com.example.touchgrass.core.sync.DeviceIdentity
import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ActiveUpsert(
    val active: Boolean,
    val started_at: String,
    val origin_device_id: String,
    val config: JsonObject
)

@Serializable
private data class ActiveClear(val active: Boolean)

/**
 * The live "start this session everywhere" broadcast. When the phone starts a
 * session with sync-to-laptop on, it upserts the account's single active_sessions
 * row; the laptop agent's run loop joins it. Cleared (active=false) on end so a
 * later device doesn't join a stale session.
 */
@Singleton
class ActiveSessionRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    private val deviceIdentity: DeviceIdentity,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun broadcast(focusMin: Int, breakMin: Int, cycles: Int, blockSites: Boolean) {
        if (!auth.isConfigured) return
        scope.launch {
            runCatching {
                val d = deviceIdentity.current()
                val config = JsonObject(
                    mapOf(
                        "focusBlockMin" to JsonPrimitive(focusMin),
                        "breakMin" to JsonPrimitive(breakMin),
                        "cycles" to JsonPrimitive(cycles),
                        "blockSites" to JsonPrimitive(blockSites)
                    )
                )
                supabase.from("active_sessions").upsert(
                    ActiveUpsert(true, Instant.now().toString(), d.id, config)
                )
            }.onFailure { Timber.tag("Sync").w(it, "active session broadcast failed") }
        }
    }

    fun clear() {
        if (!auth.isConfigured) return
        scope.launch {
            runCatching { supabase.from("active_sessions").upsert(ActiveClear(false)) }
                .onFailure { Timber.tag("Sync").w(it, "active session clear failed") }
        }
    }
}

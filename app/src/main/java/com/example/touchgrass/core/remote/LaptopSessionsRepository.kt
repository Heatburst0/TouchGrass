package com.example.touchgrass.core.remote

import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Seconds spent in one app during a session. */
data class AppTime(val name: String, val seconds: Int)

/** A finished laptop focus session with its per-app breakdown. */
data class LaptopSession(
    val id: String,
    val startedAt: String,
    val endedAt: String?,
    val focusedMin: Int,
    val cycles: Int,
    val violations: Int,
    val outcome: String,
    val productive: List<AppTime>,
    val offTask: List<AppTime>
) {
    val productiveSec: Int get() = productive.sumOf { it.seconds }
    val offTaskSec: Int get() = offTask.sumOf { it.seconds }
}

@Serializable
private data class RemoteSession(
    val id: String = "",
    val started_at: String = "",
    val ended_at: String? = null,
    val focused_min: Int = 0,
    val cycles: Int = 1,
    val violations: Int = 0,
    val outcome: String = "",
    val config: JsonObject? = null
)

private const val RECENT_LIMIT = 50L

/** Read-only history of laptop focus sessions (written by the desktop agent). */
@Singleton
class LaptopSessionsRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _sessions = MutableStateFlow<List<LaptopSession>>(emptyList())
    val sessions: StateFlow<List<LaptopSession>> = _sessions.asStateFlow()

    val isConfigured: Boolean = auth.isConfigured

    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) refresh() else _sessions.value = emptyList()
                }
            }
        }
    }

    suspend fun refresh() {
        runCatching {
            val rows = supabase.from("focus_sessions").select {
                filter { eq("deleted", false) }
                order("started_at", Order.DESCENDING)
                limit(RECENT_LIMIT)
            }.decodeList<RemoteSession>()
            _sessions.value = rows.map { it.toDomain() }
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.tag("Sync").w(it, "laptop sessions refresh failed")
        }
    }

    private fun RemoteSession.toDomain() = LaptopSession(
        id = id,
        startedAt = started_at,
        endedAt = ended_at,
        focusedMin = focused_min,
        cycles = cycles,
        violations = violations,
        outcome = outcome,
        productive = config?.appTimes("apps").orEmpty(),
        offTask = config?.appTimes("offTask").orEmpty()
    )

    private fun JsonObject.appTimes(key: String): List<AppTime> {
        val obj = (this[key] as? JsonObject) ?: return emptyList()
        return obj.entries
            .mapNotNull { (name, value) ->
                // Tolerate ints or floats; drop non-numeric / nested values.
                runCatching { value.jsonPrimitive.content.toDouble().toInt() }.getOrNull()
                    ?.let { AppTime(name, it) }
            }
            .filter { it.seconds > 0 }
            .sortedByDescending { it.seconds }
    }
}

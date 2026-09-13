package com.example.touchgrass.core.remote

import com.example.touchgrass.core.data.db.FocusSessionDao
import com.example.touchgrass.core.data.db.FocusSessionEntity
import com.example.touchgrass.core.sync.DeviceIdentity
import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** focus_sessions row shape on Supabase (snake_case columns; user_id filled by DB). */
@Serializable
private data class RemoteFocusSession(
    val id: String,
    val device_id: String? = null,
    val started_at: String,
    val ended_at: String? = null,
    val planned_focus_min: Int,
    val focused_min: Int,
    val active_min: Int = 0,
    val cycles: Int,
    val violations: Int,
    val strict: Boolean,
    val outcome: String
)

/**
 * Cross-device sync of focus sessions. On sign-in: register the device, push local
 * sessions (assigning a stable uid), then pull every device's sessions into the
 * local history. A realtime subscription re-pulls when another device records one.
 * Sessions are immutable records, so this is conflict-free (dedupe by uid).
 */
@Singleton
class SyncManager @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    private val deviceRegistrar: DeviceRegistrar,
    private val deviceIdentity: DeviceIdentity,
    private val focusSessionDao: FocusSessionDao,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile private var watchersStarted = false

    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) onSignedIn()
                }
            }
        }
    }

    private suspend fun onSignedIn() {
        runCatching {
            deviceRegistrar.register()
            syncFocusSessions()
            startWatchers()
        }.onFailure { Timber.tag("Sync").w(it, "Initial sync failed") }
    }

    suspend fun syncFocusSessions() {
        pushFocusSessions()
        pullFocusSessions()
    }

    private suspend fun pushFocusSessions() {
        val deviceId = deviceIdentity.current().id
        focusSessionDao.unsynced().forEach { s ->
            val uid = s.uid.ifBlank { UUID.randomUUID().toString() }
            supabase.from("focus_sessions").upsert(
                RemoteFocusSession(
                    id = uid,
                    device_id = deviceId,
                    started_at = Instant.ofEpochMilli(s.startedAt).toString(),
                    ended_at = Instant.ofEpochMilli(s.endedAt).toString(),
                    planned_focus_min = s.plannedFocusMin,
                    focused_min = s.focusedMin,
                    cycles = s.cycles,
                    violations = s.violations,
                    strict = s.strict,
                    outcome = s.outcome
                )
            )
            focusSessionDao.markSynced(s.id, uid)
        }
    }

    private suspend fun pullFocusSessions() {
        val remote = supabase.from("focus_sessions").select().decodeList<RemoteFocusSession>()
        remote.forEach { r ->
            if (focusSessionDao.countByUid(r.id) > 0) return@forEach
            val started = OffsetDateTime.parse(r.started_at).toInstant().toEpochMilli()
            val ended = r.ended_at?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: started
            focusSessionDao.insert(
                FocusSessionEntity(
                    startedAt = started,
                    endedAt = ended,
                    plannedFocusMin = r.planned_focus_min,
                    focusedMin = r.focused_min,
                    cycles = r.cycles,
                    violations = r.violations,
                    strict = r.strict,
                    outcome = r.outcome,
                    uid = r.id,
                    synced = true,
                    remote = true
                )
            )
        }
    }

    private fun startWatchers() {
        if (watchersStarted) return
        watchersStarted = true
        // Push newly-recorded local sessions promptly.
        scope.launch {
            focusSessionDao.observeRecent().collect { runCatching { pushFocusSessions() } }
        }
        // Pull when another device records one.
        scope.launch {
            runCatching {
                val channel = supabase.channel("focus-sync")
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "focus_sessions"
                }
                channel.subscribe()
                changes.collect { runCatching { pullFocusSessions() } }
            }.onFailure { Timber.tag("Sync").w(it, "Realtime subscription failed") }
        }
    }
}

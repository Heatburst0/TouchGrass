package com.example.touchgrass.core.remote

import com.example.touchgrass.core.data.db.FocusSessionDao
import com.example.touchgrass.core.data.db.FocusSessionEntity
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.goals.GoalOrchestrator
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.sync.DeviceIdentity
import com.example.touchgrass.di.ApplicationScope
import com.example.touchgrass.features.focus.activeFocusFromJson
import com.example.touchgrass.features.focus.insights.RecordMerger
import androidx.room.withTransaction
import com.example.touchgrass.core.data.db.TouchGrassDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import com.example.touchgrass.features.focus.insights.SessionAppUsage
import com.example.touchgrass.features.focus.insights.SessionSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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

/** focus_sessions row as pulled — includes `config`, where the laptop agent stores
 *  its per-app breakdown ({allowed, apps, offTask}). Separate from the push shape
 *  so a push never sends `config` (it would null a NOT NULL column). */
@Serializable
private data class PulledFocusSession(
    val id: String,
    val started_at: String,
    val ended_at: String? = null,
    val planned_focus_min: Int,
    val focused_min: Int,
    val cycles: Int,
    val violations: Int,
    val strict: Boolean,
    val outcome: String,
    val config: JsonObject? = null
) {
    /** The laptop agent always writes an `apps` map; phone pushes leave config empty. */
    val source: SessionSource
        get() = if (config?.containsKey("apps") == true) SessionSource.LAPTOP else SessionSource.PHONE

    val usageJson: String
        get() = SessionAppUsage.fromJsonObject(config).let { if (it.isEmpty) "" else it.toJson() }
}

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
    private val orchestrator: GoalOrchestrator,
    private val settings: SettingsRepository,
    private val database: TouchGrassDatabase,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile private var watchersStarted = false

    // Sign-in sync and the realtime/observer watchers can overlap. Serialize every push
    // and pull: otherwise two pulls can both insert (and credit) the same session, or a
    // pull can see our own just-pushed row before markSynced and insert a duplicate.
    private val syncLock = Mutex()

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

    private suspend fun pushFocusSessions() = syncLock.withLock {
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

    private suspend fun pullFocusSessions() = syncLock.withLock {
        val remote = supabase.from("focus_sessions").select().decodeList<PulledFocusSession>()
        remote.forEach { r ->
            if (focusSessionDao.countByUid(r.id) > 0) {
                // Already have it (pulled before v11, or our own): back-fill device + detail.
                focusSessionDao.fillDetail(r.id, r.source.name, r.usageJson)
                return@forEach
            }
            val started = OffsetDateTime.parse(r.started_at).toInstant().toEpochMilli()
            val ended = r.ended_at?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: started
            // Decide before the transaction (it reads DataStore, not Room).
            val credit = LaptopSessionCredit.shouldCredit(
                source = r.source,
                outcome = r.outcome,
                endedAt = ended,
                now = System.currentTimeMillis(),
                pairedWithPhone = pairedWithPhoneSession(started)
            )
            // Insert + credit atomically: a crash can't keep the row but lose the credit
            // (or credit without the row, which would re-credit on the next pull).
            database.withTransaction {
                focusSessionDao.insert(r.toEntity(started, ended))
                if (credit) orchestrator.reportProgress(GoalType.FOCUS_SESSION, 1, occurredAt = ended)
            }
        }
    }

    private fun PulledFocusSession.toEntity(started: Long, ended: Long) = FocusSessionEntity(
        startedAt = started,
        endedAt = ended,
        plannedFocusMin = planned_focus_min,
        focusedMin = focused_min,
        cycles = cycles,
        violations = violations,
        strict = strict,
        outcome = outcome,
        uid = id,
        synced = true,
        remote = true,
        source = source.name,
        appUsageJson = usageJson
    )

    /** True if this phone ran (or is running) the session a laptop one joined. */
    private suspend fun pairedWithPhoneSession(laptopStart: Long): Boolean {
        val tol = RecordMerger.PAIR_TOLERANCE_MS
        if (focusSessionDao.countLocalStartedBetween(laptopStart - tol, laptopStart + tol) > 0) return true
        val running = activeFocusFromJson(settings.activeFocusJson.first()) ?: return false
        return abs(running.startAt - laptopStart) <= tol
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

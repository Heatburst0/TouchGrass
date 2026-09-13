package com.example.touchgrass.features.focus

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.remote.AuthRepository
import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.DayOfWeek
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---- Supabase row shape (jsonb columns map to nested @Serializable) ----
@Serializable
private data class RecurrenceJson(val type: String = "DAILY", val days: List<String> = emptyList())

@Serializable
private data class ScheduleConfigJson(val blocked: List<String> = emptyList())

@Serializable
private data class RemoteSchedule(
    val id: String,
    val title: String = "Focus",
    val recurrence: RecurrenceJson = RecurrenceJson(),
    val start_local_time: String = "09:00",
    val timezone: String = "UTC",
    val focus_block_min: Int = 25,
    val break_min: Int = 5,
    val cycles: Int = 4,
    val target_platforms: List<String> = listOf("ANDROID"),
    val config: ScheduleConfigJson = ScheduleConfigJson(),
    val enabled: Boolean = true
)

/**
 * The user's recurring focus schedules, stored server-side (Supabase
 * `focus_schedules`) and mirrored locally so the alarm scheduler works offline.
 * Only schedules that target ANDROID drive the phone's auto-start.
 */
@Singleton
class FocusScheduleRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val scheduler: FocusScheduleScheduler,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _schedules = MutableStateFlow<List<FocusSchedule>>(emptyList())
    val schedules: StateFlow<List<FocusSchedule>> = _schedules.asStateFlow()

    @Volatile private var realtimeStarted = false

    init {
        scope.launch {
            // Seed from cache so UI + scheduler have data before the network.
            _schedules.value = parseCache(settings.getScheduleCacheJson())
        }
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        runCatching { refresh(); startRealtime() }
                            .onFailure { Timber.tag("Sync").w(it, "Schedule refresh failed") }
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        val remote = supabase.from("focus_schedules").select().decodeList<RemoteSchedule>()
        val cacheJson = json.encodeToString(remote)
        settings.setScheduleCacheJson(cacheJson)
        val mapped = remote.map { it.toDomain() }
        _schedules.value = mapped
        scheduler.reschedule(mapped.filter { it.enabled })
    }

    suspend fun create(schedule: FocusSchedule) {
        supabase.from("focus_schedules").upsert(schedule.toRemote())
        runCatching { refresh() }
    }

    suspend fun delete(id: String) {
        supabase.from("focus_schedules").delete {
            filter { eq("id", id) }
        }
        runCatching { refresh() }
    }

    private fun startRealtime() {
        if (realtimeStarted) return
        realtimeStarted = true
        scope.launch {
            runCatching {
                val channel = supabase.channel("schedule-sync")
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "focus_schedules"
                }
                channel.subscribe()
                changes.collect { runCatching { refresh() } }
            }.onFailure { Timber.tag("Sync").w(it, "Schedule realtime failed") }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Parse the DataStore cache into domain schedules (used offline by the receiver). */
        fun parseCache(cacheJson: String): List<FocusSchedule> {
            if (cacheJson.isBlank()) return emptyList()
            return runCatching {
                json.decodeFromString<List<RemoteSchedule>>(cacheJson).map { it.toDomain() }
            }.getOrDefault(emptyList())
        }

        private fun RemoteSchedule.toDomain(): FocusSchedule {
            val (h, m) = start_local_time.split(":").let {
                (it.getOrNull(0)?.toIntOrNull() ?: 9) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            val days: Set<DayOfWeek>? = when (recurrence.type.uppercase()) {
                "DAILY" -> null
                else -> recurrence.days.mapNotNull { d -> runCatching { DayOfWeek.valueOf(d.uppercase()) }.getOrNull() }.toSet()
            }
            return FocusSchedule(
                id = id,
                title = title,
                days = days,
                hour = h.coerceIn(0, 23),
                minute = m.coerceIn(0, 59),
                focusBlockMin = focus_block_min,
                breakMin = break_min,
                cycles = cycles,
                blockedPackages = config.blocked.toSet(),
                enabled = enabled && target_platforms.contains("ANDROID")
            )
        }

        private fun FocusSchedule.toRemote(): RemoteSchedule = RemoteSchedule(
            id = id.ifBlank { UUID.randomUUID().toString() },
            title = title.ifBlank { "Focus" },
            recurrence = if (days == null) RecurrenceJson("DAILY")
            else RecurrenceJson("CUSTOM", days.map { it.name }),
            start_local_time = "%02d:%02d".format(hour, minute),
            timezone = java.time.ZoneId.systemDefault().id,
            focus_block_min = focusBlockMin,
            break_min = breakMin,
            cycles = cycles,
            target_platforms = listOf("ANDROID"),
            config = ScheduleConfigJson(blockedPackages.toList()),
            enabled = enabled
        )
    }
}

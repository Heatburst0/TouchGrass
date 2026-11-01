package com.example.touchgrass.features.worktargets

import com.example.touchgrass.core.remote.AuthRepository
import com.example.touchgrass.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class RemoteWorkTarget(
    val id: String,
    val label: String = "",
    val app_name: String = "",
    val project: String? = null,
    val auto_focus: Boolean = false,
    val auto_focus_after_min: Int = WorkTarget.DEFAULT_AFTER_MIN,
    val focus_min: Int = WorkTarget.DEFAULT_FOCUS_MIN,
    val block_sites: Boolean = true,
    val block_phone: Boolean = true,
    val enabled: Boolean = true
)

@Serializable
private data class RemoteUsage(
    val day: String,
    val app_name: String = "",
    val project: String = "",
    val active_seconds: Int = 0
)

@Serializable
private data class RemoteDeviceProject(
    val app_name: String = "",
    val project: String = "",
    val last_seen: String? = null
)

/**
 * The user's work targets (Supabase `work_targets`, live via realtime) and the
 * laptop agent's recent `app_usage`, which quota progress is computed from, plus the
 * IDE recent-project lists it reports (`device_projects`) for the target picker.
 */
@Singleton
class WorkTargetsRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _targets = MutableStateFlow<List<WorkTarget>>(emptyList())
    val targets: StateFlow<List<WorkTarget>> = _targets.asStateFlow()

    private val _usage = MutableStateFlow<List<AppUsageRow>>(emptyList())

    /** The last [USAGE_LOOKBACK_DAYS] days of laptop usage (for progress + project suggestions). */
    val usage: StateFlow<List<AppUsageRow>> = _usage.asStateFlow()

    private val _projects = MutableStateFlow<List<DeviceProject>>(emptyList())

    /** Projects from the laptop IDEs' own recent lists, newest first — picker suggestions. */
    val projects: StateFlow<List<DeviceProject>> = _projects.asStateFlow()

    val isConfigured: Boolean = auth.isConfigured

    @Volatile private var realtimeStarted = false

    init {
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        refresh()
                        startRealtime()
                    } else {
                        _targets.value = emptyList()
                        _usage.value = emptyList()
                        _projects.value = emptyList()
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        refreshTargets()
        refreshUsage()
        refreshProjects()
    }

    /** Reload IDE recent projects; false when the server couldn't be reached. */
    suspend fun refreshProjects(): Boolean = runCatching {
        _projects.value = supabase.from("device_projects").select {
            order("last_seen", Order.DESCENDING)
            limit(PROJECTS_LIMIT)
        }.decodeList<RemoteDeviceProject>().mapNotNull { it.toDomain() }
    }.onFailure { logFailure(it, "device projects refresh failed") }.isSuccess

    /** Reload recent usage; false when the server couldn't be reached. */
    suspend fun refreshUsage(): Boolean = runCatching {
        val since = LocalDate.now().minusDays(USAGE_LOOKBACK_DAYS)
        val rows = supabase.from("app_usage").select {
            filter { gte("day", since.toString()) }
        }.decodeList<RemoteUsage>()
        _usage.value = rows.mapNotNull { it.toDomain() }
    }.onFailure { logFailure(it, "app usage refresh failed") }.isSuccess

    /** Insert or update; returns the target's id. */
    suspend fun save(target: WorkTarget): String {
        val id = target.id.ifBlank { UUID.randomUUID().toString() }
        supabase.from("work_targets").upsert(target.copy(id = id).toRemote())
        refreshTargets()
        return id
    }

    suspend fun delete(id: String) {
        supabase.from("work_targets").delete { filter { eq("id", id) } }
        refreshTargets()
    }

    /**
     * Fresh minutes on [target] in the week starting [weekStart] — for the quota
     * verifier. Asks the server for just that week and app (a handful of rows), so
     * the answer is never cut short by a response row limit. Null when offline.
     */
    suspend fun minutesInWeek(target: WorkTarget, weekStart: LocalDate): Int? = runCatching {
        val rows = supabase.from("app_usage").select {
            filter {
                gte("day", weekStart.toString())
                lt("day", weekStart.plusDays(7).toString())
                ilike("app_name", "%${escapeLike(target.appName.lowercase())}%")
            }
        }.decodeList<RemoteUsage>().mapNotNull { it.toDomain() }
        WorkTargetUsage.minutesInWeek(target, rows, weekStart)
    }.onFailure { logFailure(it, "week usage fetch failed") }.getOrNull()

    /**
     * Targets, loading them first if nothing is cached yet.
     * @throws IOException when they couldn't be loaded — "unknown" must not look like "deleted".
     */
    suspend fun currentTargets(): List<WorkTarget> {
        if (_targets.value.isEmpty() && !refreshTargets()) throw IOException("work targets unavailable")
        return _targets.value
    }

    private suspend fun refreshTargets(): Boolean = runCatching {
        _targets.value = supabase.from("work_targets").select()
            .decodeList<RemoteWorkTarget>()
            .map { it.toDomain() }
            .sortedBy { it.label.lowercase() }
    }.onFailure { logFailure(it, "work targets refresh failed") }.isSuccess

    /** Escape LIKE wildcards so an app name matches literally. */
    private fun escapeLike(value: String) =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun startRealtime() {
        if (realtimeStarted) return
        realtimeStarted = true
        scope.launch {
            runCatching {
                val channel = supabase.channel("work-targets-sync")
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "work_targets"
                }
                channel.subscribe()
                changes.collect { refreshTargets() }
            }.onFailure { logFailure(it, "work targets realtime failed") }
        }
    }

    private fun logFailure(e: Throwable, what: String) {
        if (e is CancellationException) throw e
        Timber.tag("WorkTargets").w(e, what)
    }

    private fun RemoteWorkTarget.toDomain() = WorkTarget(
        id = id,
        label = label.ifBlank { project ?: app_name },
        appName = app_name,
        project = project?.takeIf { it.isNotBlank() },
        autoFocus = auto_focus,
        autoFocusAfterMin = auto_focus_after_min,
        focusMin = focus_min,
        blockSites = block_sites,
        blockPhone = block_phone,
        enabled = enabled
    )

    private fun WorkTarget.toRemote() = RemoteWorkTarget(
        id = id,
        label = label.trim().ifBlank { project ?: appName },
        app_name = appName.lowercase(),
        project = project?.trim()?.takeIf { it.isNotEmpty() },
        auto_focus = autoFocus,
        auto_focus_after_min = autoFocusAfterMin.coerceIn(WorkTarget.AFTER_MIN_RANGE),
        focus_min = focusMin.coerceIn(WorkTarget.FOCUS_MIN_RANGE),
        block_sites = blockSites,
        block_phone = blockPhone,
        enabled = enabled
    )

    private fun RemoteUsage.toDomain(): AppUsageRow? = runCatching {
        AppUsageRow(LocalDate.parse(day), app_name, project, active_seconds)
    }.getOrNull()

    private fun RemoteDeviceProject.toDomain(): DeviceProject? {
        if (app_name.isBlank() || project.isBlank()) return null
        val seen = last_seen?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        return DeviceProject(app_name, project, seen)
    }

    private companion object {
        const val USAGE_LOOKBACK_DAYS = 35L
        const val PROJECTS_LIMIT = 500L
    }
}

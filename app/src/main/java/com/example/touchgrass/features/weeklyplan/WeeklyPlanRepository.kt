package com.example.touchgrass.features.weeklyplan

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.GoalDao
import com.example.touchgrass.core.discipline.LockWindow
import com.example.touchgrass.core.discipline.PlanField
import com.example.touchgrass.core.discipline.UnlockRequest
import com.example.touchgrass.core.discipline.WeeklyLock
import com.example.touchgrass.core.discipline.WeeklyPlan
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.goals.Recurrence
import com.example.touchgrass.core.goals.newPledgeGoal
import com.example.touchgrass.core.manager.ShortsTrackerManager
import com.example.touchgrass.core.remote.AuthRepository
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI needs: the plan, whether it's committed, the lock, and unlock requests. */
data class WeeklyPlanState(
    val committed: Boolean,
    val plan: WeeklyPlan,
    val window: LockWindow,
    val pendingUnlocks: Map<PlanField, Long>, // field -> when the paid unlock becomes ready
    val readyUnlocks: Set<PlanField>,
    val asOfMs: Long // trusted "now" these values were computed at (for countdowns)
) {
    /** The lock is only real once the user has committed a plan. */
    val locked: Boolean get() = committed && window.locked
}

/** Result of a change: what was saved, and which requested loosenings were refused. */
data class PlanUpdate(val saved: WeeklyPlan, val refused: Set<PlanField>)

/**
 * The single gate for every weekly-lockable setting (force read, Shorts limit, blocked
 * apps, weekly reading pages, accessibility penalty, focus quotas). Every edit — from
 * the Weekly plan hub or from a feature's own screen — goes through [update], which
 * applies the tighten-only rule ([WeeklyLock]) while locked, then writes the result
 * into the real settings.
 *
 * Tamper resistance: the plan lives server-side (Supabase `weekly_plans`) so clearing
 * data restores it at sign-in; the lock is judged on SERVER time anchored to the
 * boot-relative clock, so changing the phone's clock can't fake the weekend.
 */
@Singleton
class WeeklyPlanRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val shorts: ShortsTrackerManager,
    private val goalDao: GoalDao,
    private val rewards: RewardsManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val lock = Mutex()

    /** Nothing may read or write the plan before the cached one is loaded (it'd clobber it). */
    private val loaded = CompletableDeferred<Unit>()

    private var snapshot = Snapshot(committed = false, plan = WeeklyPlan(), unlocks = emptyList(), anchor = null)
    private val _state = MutableStateFlow(stateOf(snapshot))
    val state: StateFlow<WeeklyPlanState> = _state.asStateFlow()

    /** Live plan, for enforcement code that just needs a value (e.g. the penalty switch). */
    val plan: WeeklyPlan get() = snapshot.plan

    init {
        scope.launch {
            lock.withLock {
                snapshot = loadCache() ?: Snapshot(false, draftFromSettings(), emptyList(), null)
                publish()
            }
            loaded.complete(Unit)
            // Repository-owned clock: crosses Mon/Sat boundaries, expires unlocks and
            // retries unpushed changes even when no weekly-plan screen is open.
            while (true) {
                delay(TICK_MS)
                locked { tick() }
            }
        }
        if (auth.isConfigured) {
            scope.launch {
                auth.sessionStatus.collect { if (it is SessionStatus.Authenticated) syncFromServer() }
            }
        }
    }

    /** Pages read so far toward the plan's weekly reading goal (null when there's no target). */
    val readingProgress: Flow<Int?> = goalDao.observeAll().map { goals ->
        goals.firstOrNull { it.active && JSONObject(it.configJson).optBoolean(PLAN_GOAL_MARKER) }?.progress
    }

    /** Re-evaluate the lock (call from a UI ticker so the countdown/unlock state stays fresh). */
    fun refresh() = scope.launch { locked { publish() } }

    /** Fresh lock state for enforcement code (never a stale StateFlow value). */
    fun currentState(): WeeklyPlanState = stateOf(snapshot)

    /**
     * Apply [transform] to the plan. Unlocked (weekend, or nothing committed yet): saved
     * as-is. Locked: only same-or-stricter fields are applied, plus fields whose paid
     * unlock is ready (each ready unlock is used up by the loosening it allows).
     */
    suspend fun update(transform: (WeeklyPlan) -> WeeklyPlan): PlanUpdate = locked {
        val current = snapshot.plan
        val proposed = transform(current)
        val st = stateOf(snapshot)
        val window = if (snapshot.committed) st.window else LockWindow(locked = false, lockedUntil = null, nextLockAt = null)
        val loosening = if (window.locked) WeeklyLock.looseningFields(current, proposed) else emptySet()
        val saved = WeeklyLock.applyChange(current, proposed, window, st.readyUnlocks)
        val usedUnlocks = loosening intersect st.readyUnlocks
        snapshot = snapshot.copy(plan = saved, unlocks = snapshot.unlocks.filterNot { it.field in usedUnlocks })
        persistAndApply()
        PlanUpdate(saved, refused = loosening - st.readyUnlocks)
    }

    /** Commit the current plan: from now on it's locked Mon–Fri (tighten-only). */
    suspend fun commit() = locked {
        snapshot = snapshot.copy(committed = true, zoneId = ZoneId.systemDefault().id, ownerId = auth.currentUserId())
        persistAndApply()
    }

    /**
     * Pay [WeeklyLock.UNLOCK_COST_POINTS] to loosen [field] after a 24 h cool-off. Fails
     * if not locked, already requested, or the balance can't cover it (debt blocks it).
     */
    suspend fun requestUnlock(field: PlanField): Boolean = locked {
        val st = stateOf(snapshot)
        if (!st.locked || snapshot.unlocks.any { it.field == field }) return@locked false
        if (!rewards.spend(WeeklyLock.UNLOCK_COST_POINTS, "weekly_unlock:$field")) return@locked false
        snapshot = snapshot.copy(unlocks = snapshot.unlocks + UnlockRequest(field, trustedNow()))
        persistAndApply()
        true
    }

    // ---------------------------------------------------------------- internals

    private suspend fun <T> locked(block: suspend () -> T): T {
        loaded.await()
        return lock.withLock { block() }
    }

    /**
     * Saved locally first and marked unpushed, so an offline change isn't later
     * overwritten by the older server row (see [syncFromServer]).
     */
    private suspend fun persistAndApply() {
        snapshot = snapshot.copy(pendingPush = true)
        saveCache(snapshot)
        publish()
        applyToSettings(snapshot.plan)
        if (pushToServer(snapshot)) {
            snapshot = snapshot.copy(pendingPush = false)
            saveCache(snapshot)
        }
    }

    private suspend fun publish() {
        val before = snapshot
        val st = stateOf(snapshot)
        // Unlocks belong to the lock window they were bought in: drop them at the weekend
        // and drop any from an earlier week (the app may not have run over the weekend).
        snapshot = snapshot.copy(unlocks = snapshot.unlocks.filter { unlockLive(it, st) })
        // The commitment zone can only change while unlocked, so hopping timezones
        // mid-week can't fake a weekend.
        if (!st.locked && snapshot.zoneId != ZoneId.systemDefault().id) {
            snapshot = snapshot.copy(zoneId = ZoneId.systemDefault().id)
        }
        if (snapshot != before) saveCache(snapshot)
        _state.value = stateOf(snapshot)
    }

    private suspend fun tick() {
        publish()
        if (snapshot.pendingPush && pushToServer(snapshot)) {
            snapshot = snapshot.copy(pendingPush = false)
            saveCache(snapshot)
        }
        // Detector test mode stops real counting/blocking, so it's off while locked.
        if (stateOf(snapshot).locked && shorts.testMode.value) shorts.setTestMode(false)
    }

    private fun unlockLive(u: UnlockRequest, st: WeeklyPlanState): Boolean {
        val until = st.window.lockedUntil?.toEpochMilli() ?: return false
        return st.window.locked && u.requestedAtMs >= until - LOCK_SPAN_MS && u.requestedAtMs < until
    }

    private fun stateOf(s: Snapshot): WeeklyPlanState {
        val now = trustedNow(s)
        val zone = s.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        var window = WeeklyLock.lockWindow(Instant.ofEpochMilli(now), zone)
        // After a reboot the server anchor is gone and the phone clock can't be trusted
        // to announce the weekend: keep a committed plan locked until the next sync.
        if (s.committed && !window.locked && !clockTrusted(s)) {
            window = LockWindow(locked = true, lockedUntil = null, nextLockAt = null)
        }
        val base = WeeklyPlanState(s.committed, s.plan, window, emptyMap(), emptySet(), now)
        val live = s.unlocks.filter { unlockLive(it, base) }
        return base.copy(
            pendingUnlocks = live.associate { it.field to WeeklyLock.unlockReadyAt(it) },
            readyUnlocks = live.filter { WeeklyLock.isUnlockReady(it, now) }.map { it.field }.toSet()
        )
    }

    /** Server time carried forward on the boot clock; device time only as a fallback. */
    private fun trustedNow(s: Snapshot = snapshot): Long {
        val a = s.anchor ?: return System.currentTimeMillis()
        return if (a.boot == bootCount()) a.serverMs + (SystemClock.elapsedRealtime() - a.elapsedMs)
        else System.currentTimeMillis() // rebooted since the last server sync; resync soon
    }

    /** True with a same-boot server anchor, or when there's no server at all to ask. */
    private fun clockTrusted(s: Snapshot): Boolean =
        !auth.isConfigured || s.anchor?.boot == bootCount()

    /** Write the effective plan into the settings the enforcement code already reads. */
    private suspend fun applyToSettings(plan: WeeklyPlan) {
        shorts.updateShortsLimit(plan.shortsDailyLimit)
        settings.setReadingGateEnabled(plan.forceReadEnabled)
        if (!plan.forceReadEnabled) settings.setReadingGatePending(false)
        settings.setNudgeIntervalMinutes(plan.forceReadEveryMin)
        settings.setFocusBlockedPackages(plan.blockedPackages)
        syncReadingGoal(plan.weeklyReadingPages)
    }

    /** Keep one recurring weekly READING goal in step with the plan's page target. */
    private suspend fun syncReadingGoal(pages: Int) {
        val existing = goalDao.observeAll().first()
            .firstOrNull { it.active && JSONObject(it.configJson).optBoolean(PLAN_GOAL_MARKER) }
        when {
            pages <= 0 -> existing?.let { goalDao.upsert(it.copy(active = false)) }
            existing == null -> {
                val goal = newPledgeGoal(
                    goalType = GoalType.READING, category = "Reading", unit = "pages",
                    title = "Weekly plan: read $pages pages", target = pages, deadlineAt = 0,
                    reward = READING_GOAL_REWARD, penalty = READING_GOAL_PENALTY_SHORTS,
                    now = System.currentTimeMillis(), recurrence = Recurrence.Weekly
                )
                goalDao.upsert(goal.copy(configJson = JSONObject(goal.configJson).put(PLAN_GOAL_MARKER, true).toString()))
            }
            existing.target != pages ->
                goalDao.upsert(existing.copy(target = pages, title = "Weekly plan: read $pages pages"))
        }
    }

    /** First run: describe what the user already has, so the draft plan changes nothing. */
    private suspend fun draftFromSettings() = WeeklyPlan(
        forceReadEnabled = settings.readingGateEnabled.first(),
        forceReadEveryMin = settings.nudgeIntervalMinutes.first(),
        shortsDailyLimit = settings.shortsLimit.first(),
        blockedPackages = settings.focusBlockedPackages.first(),
        weeklyReadingPages = 0,
        accessibilityPenaltyEnabled = true
    )

    // ---------------------------------------------------------------- server

    // Fetch and apply under the same lock as edits, so a response can't land on top
    // of a newer local change.
    private suspend fun syncFromServer() = locked {
        runCatching {
            val serverMs = OffsetDateTime.parse(
                supabase.postgrest.rpc("server_now").decodeAs<String>()
            ).toInstant().toEpochMilli()
            val anchor = Anchor(serverMs, SystemClock.elapsedRealtime(), bootCount())
            val remote = supabase.from("weekly_plans").select().decodeList<RemoteWeeklyPlan>().firstOrNull()
            val user = auth.currentUserId()
            if (snapshot.ownerId != null && snapshot.ownerId != user) {
                // Another account's cached plan: never carry its lock or unlocks over.
                snapshot = Snapshot(false, snapshot.plan, emptyList(), null)
            }
            run {
                // A pending local change only beats the server if this install has synced
                // before; a fresh install (cleared data) always takes the server's plan.
                val keepLocal = snapshot.pendingPush && snapshot.anchor != null
                snapshot = if (remote?.plan != null && !keepLocal) {
                    // Server wins: clearing data / reinstalling must not reset a committed lock.
                    Snapshot(
                        remote.committed, remote.plan.toDomain(),
                        remote.unlock_requests.mapNotNull { runCatching { it.toDomain() }.getOrNull() }, anchor,
                        zoneId = remote.timezone, ownerId = user
                    )
                } else {
                    // No row yet, or a local change never reached the server: keep local, push it.
                    snapshot.copy(anchor = anchor, ownerId = user)
                }
                if (snapshot.pendingPush || remote?.plan == null) {
                    if (pushToServer(snapshot)) snapshot = snapshot.copy(pendingPush = false)
                }
                saveCache(snapshot)
                publish()
                applyToSettings(snapshot.plan)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.tag("WeeklyPlan").w(it, "server sync failed (offline or migration not applied)")
        }
    }

    /** True when the server now holds [s]. */
    private suspend fun pushToServer(s: Snapshot): Boolean {
        if (!auth.isConfigured || auth.sessionStatus.value !is SessionStatus.Authenticated) return false
        return runCatching {
            supabase.from("weekly_plans").upsert(
                RemoteWeeklyPlan(
                    plan = s.plan.toDto(),
                    committed = s.committed,
                    unlock_requests = s.unlocks.map { it.toDto() },
                    timezone = s.zoneId
                )
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.tag("WeeklyPlan").w(it, "push failed; kept locally")
        }.isSuccess
    }

    // ---------------------------------------------------------------- cache

    private suspend fun loadCache(): Snapshot? {
        val raw = settings.getWeeklyPlanCacheJson()
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<CacheDto>(raw).toDomain() }.getOrNull()
    }

    private suspend fun saveCache(s: Snapshot) {
        settings.setWeeklyPlanCacheJson(json.encodeToString(CacheDto.serializer(), s.toCache()))
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private data class Anchor(val serverMs: Long, val elapsedMs: Long, val boot: Int)

    private data class Snapshot(
        val committed: Boolean,
        val plan: WeeklyPlan,
        val unlocks: List<UnlockRequest>,
        val anchor: Anchor?,
        val pendingPush: Boolean = false,
        val zoneId: String? = null,  // commitment timezone (changes only take effect when unlocked)
        val ownerId: String? = null  // account the cached plan belongs to
    )

    @Serializable
    private data class PlanDto(
        val forceReadEnabled: Boolean = false,
        val forceReadEveryMin: Int = 60,
        val shortsDailyLimit: Int = 20,
        val blockedPackages: List<String> = emptyList(),
        val weeklyReadingPages: Int = 0,
        val accessibilityPenaltyEnabled: Boolean = true,
        val focusQuotaMinutes: Map<String, Int> = emptyMap()
    )

    @Serializable
    private data class UnlockDto(val field: String, val requestedAtMs: Long)

    @Serializable
    private data class AnchorDto(val serverMs: Long, val elapsedMs: Long, val boot: Int)

    @Serializable
    private data class CacheDto(
        val committed: Boolean = false,
        val plan: PlanDto = PlanDto(),
        val unlocks: List<UnlockDto> = emptyList(),
        val anchor: AnchorDto? = null,
        val pendingPush: Boolean = false,
        val zoneId: String? = null,
        val ownerId: String? = null
    )

    /** weekly_plans row (user_id + updated_at are filled by the database). */
    @Serializable
    private data class RemoteWeeklyPlan(
        val plan: PlanDto? = null,
        val committed: Boolean = false,
        val unlock_requests: List<UnlockDto> = emptyList(),
        val timezone: String? = null
    )

    private fun WeeklyPlan.toDto() = PlanDto(
        forceReadEnabled, forceReadEveryMin, shortsDailyLimit, blockedPackages.toList(),
        weeklyReadingPages, accessibilityPenaltyEnabled, focusQuotaMinutes
    )

    private fun PlanDto.toDomain() = WeeklyPlan(
        forceReadEnabled, forceReadEveryMin, shortsDailyLimit, blockedPackages.toSet(),
        weeklyReadingPages, accessibilityPenaltyEnabled, focusQuotaMinutes
    )

    private fun UnlockRequest.toDto() = UnlockDto(field.name, requestedAtMs)

    private fun UnlockDto.toDomain() = UnlockRequest(PlanField.valueOf(field), requestedAtMs)

    private fun Snapshot.toCache() = CacheDto(
        committed, plan.toDto(), unlocks.map { it.toDto() },
        anchor?.let { AnchorDto(it.serverMs, it.elapsedMs, it.boot) },
        pendingPush, zoneId, ownerId
    )

    private fun CacheDto.toDomain() = Snapshot(
        committed, plan.toDomain(),
        unlocks.mapNotNull { runCatching { it.toDomain() }.getOrNull() },
        anchor?.let { Anchor(it.serverMs, it.elapsedMs, it.boot) },
        pendingPush, zoneId, ownerId
    )

    companion object {
        private const val PLAN_GOAL_MARKER = "weeklyPlan"
        private const val TICK_MS = 60_000L
        private const val LOCK_SPAN_MS = 5 * 24 * 60 * 60_000L // Mon 00:00 -> Sat 00:00
        private const val READING_GOAL_REWARD = 50
        private const val READING_GOAL_PENALTY_SHORTS = 5
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

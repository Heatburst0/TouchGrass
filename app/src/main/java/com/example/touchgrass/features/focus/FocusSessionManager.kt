package com.example.touchgrass.features.focus

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.FocusSessionDao
import com.example.touchgrass.core.data.db.FocusSessionEntity
import com.example.touchgrass.core.goals.GoalOrchestrator
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.notifications.NotifChannel
import com.example.touchgrass.core.notifications.Notifier
import com.example.touchgrass.core.remote.ActiveSessionRepository
import com.example.touchgrass.di.ApplicationScope
import com.example.touchgrass.features.focus.insights.AppCategoryLookup
import com.example.touchgrass.features.focus.insights.SessionAppUsage
import com.example.touchgrass.features.focus.insights.SessionSource
import com.example.touchgrass.features.focus.insights.UsageEventsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the (single) active focus session. The session is stored as start-time +
 * config in DataStore, so the phase is a pure function of the wall clock — it
 * survives process death without a foreground service. The accessibility service
 * reads [shouldBlock] synchronously to bounce distractions during focus blocks.
 *
 * Completion is finalized in one place ([settleInternal]) — reachable from the
 * end-of-session alarm, an early End, or the next app open — and is idempotent
 * (a session's start-time records it exactly once). A finished session is written
 * to history and, if completed, credited to any active FOCUS_SESSION goal.
 */
@Singleton
class FocusSessionManager @Inject constructor(
    private val settings: SettingsRepository,
    private val sessionDao: FocusSessionDao,
    private val scheduler: FocusScheduler,
    private val notifier: Notifier,
    private val orchestrator: GoalOrchestrator,
    private val activeSessions: ActiveSessionRepository,
    private val usageEvents: UsageEventsSource,
    private val appCategories: AppCategoryLookup,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile
    private var cached: ActiveFocus? = null

    /** The active session (or null). The UI ticks a 1s clock against it. */
    val activeSession: StateFlow<ActiveFocus?> =
        settings.activeFocusJson
            .map { activeFocusFromJson(it) }
            .onEach { cached = it }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Recent finished sessions, newest first. */
    val recentSessions = sessionDao.observeRecent()

    /** Aggregate stats for the header ("N successful"). */
    val stats = sessionDao.observeStats()

    /** Distraction opens attempted during focus blocks this session. */
    var violations = 0
        private set

    fun start(config: FocusConfig, syncToLaptop: Boolean = false, blockSites: Boolean = false) {
        violations = 0
        if (syncToLaptop) {
            activeSessions.broadcast(config.focusBlockMin, config.breakMin, config.cycles, blockSites)
        }
        scope.launch {
            val active = ActiveFocus(System.currentTimeMillis(), config)
            settings.setActiveFocusJson(active.toJson())
            scheduler.scheduleEnd(active.endAt)
            notifier.postFocusCountdown(
                title = if (config.strict) "Focus (strict) — stay on task" else "Focus session running",
                body = "${config.cycles} × ${config.focusBlockMin}m focus" +
                    (if (config.breakMin > 0) " · ${config.breakMin}m breaks" else "") +
                    " · ${config.totalMinutes}m total",
                endAt = active.endAt,
                strict = config.strict
            )
        }
    }

    /** Auto-start from a schedule — but never interrupt a session already running. */
    fun startScheduled(config: FocusConfig) {
        if (cached != null) return
        start(config)
    }

    /** User ended the session (non-strict). Records it (early or complete) and clears. */
    fun endEarly() {
        scope.launch { currentActive()?.let { settleInternal(it, clear = true) } }
    }

    /** "New session" after a completed run — just drop the finished session to idle. */
    fun clearToIdle() {
        scope.launch {
            scheduler.cancel()
            notifier.cancel(Notifier.Ids.FOCUS_ONGOING)
            activeSessions.clear()
            settings.setActiveFocusJson("")
        }
    }

    /** Finalize only if the session has naturally completed (alarm / app-open / worker).
     *  Keeps the session visible so the UI can show the Done summary. */
    fun settleIfComplete(onFinished: (() -> Unit)? = null) {
        scope.launch {
            val a = currentActive()
            if (a != null && focusPhaseAt(a, System.currentTimeMillis()) is FocusPhase.Done) {
                settleInternal(a, clear = false)
            }
            onFinished?.invoke()
        }
    }

    // Alarm, End button and app-open can all settle the same session; serialize them so
    // the "already recorded?" check and the insert can't interleave (no duplicate rows).
    private val settleLock = Mutex()

    private suspend fun settleInternal(active: ActiveFocus, clear: Boolean) = settleLock.withLock {
        val now = System.currentTimeMillis()
        val done = focusPhaseAt(active, now) is FocusPhase.Done
        if (sessionDao.countByStart(active.startAt) == 0) {
            val outcome = if (done) FocusOutcome.COMPLETED else FocusOutcome.ENDED_EARLY
            val focused = if (done) active.config.focusMinutes else focusedMinutesAt(active, now)
            // A completed session may be settled late (next app open): measure usage only
            // up to its real end, not app use that happened afterwards.
            val usageEnd = if (done) minOf(now, active.endAt) else now
            sessionDao.insert(
                FocusSessionEntity(
                    startedAt = active.startAt,
                    endedAt = now,
                    plannedFocusMin = active.config.focusMinutes,
                    focusedMin = focused,
                    cycles = active.config.cycles,
                    violations = violations,
                    strict = active.config.strict,
                    outcome = outcome.name,
                    source = SessionSource.PHONE.name,
                    appUsageJson = phoneUsageJson(active, usageEnd)
                )
            )
            if (outcome == FocusOutcome.COMPLETED) {
                orchestrator.reportProgress(GoalType.FOCUS_SESSION, 1)
                notifier.post(
                    NotifChannel.GOALS, Notifier.Ids.FOCUS_DONE,
                    title = "Focus session complete",
                    body = "${active.config.focusMinutes} focus min done" +
                        if (violations > 0) " · $violations distraction${if (violations == 1) "" else "s"} blocked" else " · clean run"
                )
            }
        }
        notifier.cancel(Notifier.Ids.FOCUS_ONGOING)
        scheduler.cancel()
        activeSessions.clear()
        if (clear) settings.setActiveFocusJson("")
    }

    /** Per-app phone time during the session, split into time wasted (blocked, or a
     *  social / video / game / news app) vs everything else. "" if usage access is off. */
    private suspend fun phoneUsageJson(active: ActiveFocus, end: Long): String {
        val perApp = usageEvents.foregroundSeconds(active.startAt, end)
        if (perApp.isEmpty()) return ""
        val blocked = active.config.blockedPackages
        val (wasted, other) = perApp.entries.partition { (pkg, _) ->
            pkg in blocked || appCategories.isDistracting(pkg)
        }
        return SessionAppUsage(
            apps = other.associate { it.key to it.value },
            offTask = wasted.associate { it.key to it.value }
        ).toJson()
    }

    private suspend fun currentActive(): ActiveFocus? =
        cached ?: activeFocusFromJson(settings.activeFocusJson.first())

    fun phaseNow(now: Long = System.currentTimeMillis()): FocusPhase = focusPhaseAt(cached, now)

    /** Synchronous read for the accessibility service. */
    fun shouldBlock(packageName: String): Boolean {
        val a = cached ?: return false
        return focusPhaseAt(a, System.currentTimeMillis()) is FocusPhase.Focusing &&
            packageName in a.config.blockedPackages
    }

    fun registerViolation() {
        violations++
    }
}

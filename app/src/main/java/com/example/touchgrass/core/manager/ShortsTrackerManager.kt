package com.example.touchgrass.core.manager

import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.analyzer.YouTubeShortsDetector
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.model.ScreenState
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

// The data object exposed to the Jetpack Compose UI
data class ShortsStats(
    val totalCount: Int = 0,
    val totalTimeMillis: Long = 0L
) {
    val averageTimeSeconds: Long
        get() = if (totalCount == 0) 0 else (totalTimeMillis / totalCount) / 1000
}

@Singleton
class ShortsTrackerManager @Inject constructor(
    private val detector: YouTubeShortsDetector,
    private val settings: SettingsRepository,
    private val rewards: RewardsManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _stats = MutableStateFlow(ShortsStats())
    val stats: StateFlow<ShortsStats> = _stats.asStateFlow()

    /** Base daily limit set by the user. */
    val shortsLimit: StateFlow<Int> = settings.shortsLimit
        .stateIn(scope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_LIMIT)

    /**
     * What actually gates blocking: base limit + shorts earned back by reading
     * − shorts docked for missed commitments (floored at 0).
     */
    val effectiveLimit: StateFlow<Int> =
        combine(
            shortsLimit,
            rewards.extraShortsToday,
            rewards.penaltyShortsToday
        ) { base, extra, penalty ->
            (base + extra - penalty).coerceAtLeast(0)
        }.stateIn(scope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_LIMIT)

    private val _triggerBlockEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val triggerBlockEvent = _triggerBlockEvent.asSharedFlow()

    private var currentFingerprint: String? = null
    private var watchStartAt = 0L        // when the current short began being watched (0 = paused)
    private var lastEventAt = 0L         // timestamp of the previous processed event
    private var lastBlockAt = 0L
    private var lastPersistAt = 0L
    private var todayKey: String = LocalDate.now().toString()

    init {
        // Restore today's stats so a process kill (or reboot) doesn't wipe progress.
        scope.launch {
            val persisted = settings.loadToday()
            todayKey = persisted.dateKey
            // Merge instead of overwrite: events may have arrived before the load finished.
            _stats.update {
                ShortsStats(
                    totalCount = it.totalCount + persisted.count,
                    totalTimeMillis = it.totalTimeMillis + persisted.timeMillis
                )
            }
            Timber.tag("ShortsTracker").i(
                "Restored today's stats: %d shorts, %d ms", persisted.count, persisted.timeMillis
            )
        }
    }

    fun updateShortsLimit(newLimit: Int) {
        scope.launch { settings.setShortsLimit(newLimit) }
    }

    fun processAccessibilityEvent(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        rolloverIfNewDay()

        // A long gap since our last event means the app was backgrounded or the
        // screen was off. Bank the time up to the LAST event we actually saw (not
        // "now") and pause — so away-time is never billed as watch time.
        if (lastEventAt > 0L && now - lastEventAt > AWAY_GAP_MS) {
            bankWatch(lastEventAt)
        }

        when (val state = detector.detect(rootNode)) {
            is ScreenState.WatchingShort -> {
                if (state.uniqueId != currentFingerprint) {
                    // A genuinely different video (channel or duration changed).
                    bankWatch(now)                    // finalize the previous short's time
                    currentFingerprint = state.uniqueId
                    watchStartAt = now                // start timing this one
                    _stats.update { it.copy(totalCount = it.totalCount + 1) }
                    Timber.tag("ShortsTracker")
                        .d(">>> NEW SHORT: ${state.uniqueId} | Total: ${_stats.value.totalCount}")
                    persist(force = true)
                } else if (watchStartAt == 0L) {
                    watchStartAt = now                // resume timing the same short
                }

                if (_stats.value.totalCount >= effectiveLimit.value) {
                    maybeTriggerBlock(now)
                }
                persist()
            }
            is ScreenState.BrowsingFeed -> {
                // Pause the timer but KEEP the fingerprint, so returning to the same
                // short (after comments / pause / tab-switch) doesn't recount it.
                bankWatch(now)
            }
            is ScreenState.Unknown -> {
                // Transitioning, or identity unreadable this frame — ignore.
            }
        }

        lastEventAt = now
    }

    /**
     * Dwell-based timing: bill the wall-clock time a single short was actually on
     * screen (from [watchStartAt] up to [upTo]), then pause. Robust to how often
     * YouTube fires accessibility events, unlike the old per-event heartbeat.
     */
    private fun bankWatch(upTo: Long) {
        if (watchStartAt <= 0L) return
        val dwell = (upTo - watchStartAt).coerceAtMost(MAX_SHORT_MS)
        if (dwell > 0L) {
            _stats.update { it.copy(totalTimeMillis = it.totalTimeMillis + dwell) }
            persist(force = true)
        }
        watchStartAt = 0L
    }

    private fun maybeTriggerBlock(now: Long) {
        if (now - lastBlockAt >= BLOCK_COOLDOWN_MS) {
            lastBlockAt = now
            _triggerBlockEvent.tryEmit(Unit)
        }
    }

    private fun rolloverIfNewDay() {
        val today = LocalDate.now().toString()
        if (today != todayKey) {
            todayKey = today
            currentFingerprint = null
            watchStartAt = 0L
            _stats.value = ShortsStats()
            persist(force = true)
            Timber.tag("ShortsTracker").i("New day (%s) — stats reset", today)
        }
    }

    /** Writes are throttled so scroll-spam doesn't hammer DataStore every 500ms. */
    private fun persist(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        val snapshot = _stats.value
        scope.launch { settings.saveToday(snapshot.totalCount, snapshot.totalTimeMillis) }
    }

    companion object {
        private const val MAX_SHORT_MS = 300_000L   // cap one short's billed dwell (5 min)
        private const val AWAY_GAP_MS = 15_000L     // gap between events ⇒ app was backgrounded
        private const val BLOCK_COOLDOWN_MS = 5_000L
        private const val PERSIST_INTERVAL_MS = 5_000L
    }
}

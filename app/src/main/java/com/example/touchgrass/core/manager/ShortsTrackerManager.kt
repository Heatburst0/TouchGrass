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

    /** What actually gates blocking: base limit + shorts earned back via reading. */
    val effectiveLimit: StateFlow<Int> =
        combine(shortsLimit, rewards.extraShortsToday) { base, extra -> base + extra }
            .stateIn(scope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_LIMIT)

    private val _triggerBlockEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val triggerBlockEvent = _triggerBlockEvent.asSharedFlow()

    private var currentShortId: String? = null
    private var lastHeartbeatAt = 0L
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

        when (val state = detector.detect(rootNode)) {
            is ScreenState.WatchingShort -> {
                if (state.uniqueId != currentShortId) {
                    currentShortId = state.uniqueId
                    lastHeartbeatAt = now
                    _stats.update { it.copy(totalCount = it.totalCount + 1) }
                    Timber.tag("ShortsTracker")
                        .d(">>> NEW SHORT: ${state.uniqueId} | Total: ${_stats.value.totalCount}")
                    persist(force = true)
                } else {
                    accumulateWatchTime(now)
                }

                // Re-fires (with a cooldown) if the user sneaks back into Shorts after a block.
                if (_stats.value.totalCount >= effectiveLimit.value) {
                    maybeTriggerBlock(now)
                }
                persist()
            }
            is ScreenState.BrowsingFeed -> {
                if (currentShortId != null) {
                    accumulateWatchTime(now)
                    currentShortId = null
                    Timber.tag("ShortsTracker").d(">>> SESSION ENDED. Browsing feed.")
                    persist(force = true)
                }
            }
            is ScreenState.Unknown -> {
                // Transitioning or UI is flickering (e.g. buffering).
                // Do nothing and let the heartbeat resume on the next confident detection.
            }
        }
    }

    /**
     * Heartbeat-based time tracking: only count gaps between consecutive events while
     * a short is on screen. Leaving the app stops events (package filter), so away-time
     * larger than [MAX_HEARTBEAT_GAP_MS] is discarded instead of billed as watch time.
     */
    private fun accumulateWatchTime(now: Long) {
        val delta = now - lastHeartbeatAt
        if (delta in 1..MAX_HEARTBEAT_GAP_MS) {
            _stats.update { it.copy(totalTimeMillis = it.totalTimeMillis + delta) }
        }
        lastHeartbeatAt = now
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
            currentShortId = null
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
        private const val MAX_HEARTBEAT_GAP_MS = 10_000L
        private const val BLOCK_COOLDOWN_MS = 5_000L
        private const val PERSIST_INTERVAL_MS = 5_000L
    }
}

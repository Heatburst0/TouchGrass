package com.example.touchgrass.core.manager

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.analyzer.YouTubeShortsDetector
import com.example.touchgrass.core.model.ScreenState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val detector: YouTubeShortsDetector
) {
    private val _stats = MutableStateFlow(ShortsStats())
    val stats: StateFlow<ShortsStats> = _stats.asStateFlow()
    var shortsLimit: Int = 5
    private val _triggerBlockEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val triggerBlockEvent = _triggerBlockEvent.asSharedFlow()

    private var currentShortId: String? = null
    private var currentShortStartTime: Long = 0L

    fun processAccessibilityEvent(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()

        when (val state = detector.detect(rootNode)) {
            is ScreenState.WatchingShort -> {
                if (state.uniqueId != currentShortId) {
                    // 1. If we were already watching a short, finalize its time
                    finalizeCurrentShort(now)

                    // 2. Start tracking the new short
                    currentShortId = state.uniqueId
                    currentShortStartTime = now

                    _stats.update { it.copy(totalCount = it.totalCount + 1) }
                    Log.d("ShortsTracker", ">>> NEW SHORT: ${state.uniqueId} | Total: ${_stats.value.totalCount}")

                    // NEW: Check if the limit has been reached
                    if (_stats.value.totalCount >= shortsLimit) {
                        _triggerBlockEvent.tryEmit(Unit)
                    }
                }
            }
            is ScreenState.BrowsingFeed -> {
                // User left the Shorts player, finalize the time and reset state
                if (currentShortId != null) {
                    finalizeCurrentShort(now)
                    Log.d("ShortsTracker", ">>> SESSION ENDED. Browsing feed.")
                    currentShortId = null
                }
            }
            is ScreenState.Unknown -> {
                // Transitioning or UI is flickering (e.g. buffering).
                // Do nothing and let the timer keep running.
            }
        }
    }

    private fun finalizeCurrentShort(endTime: Long) {
        if (currentShortId != null && currentShortStartTime > 0) {
            val duration = endTime - currentShortStartTime

            // Safeguard: Only record if the duration is a valid positive number
            if (duration > 0) {
                _stats.update { it.copy(totalTimeMillis = it.totalTimeMillis + duration) }
            }

            currentShortStartTime = 0L // Reset timer for the next video
        }
    }
}
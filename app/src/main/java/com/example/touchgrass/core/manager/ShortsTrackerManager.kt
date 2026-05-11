package com.example.touchgrass.core.manager

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.analyzer.YouTubeShortsDetector
import com.example.touchgrass.core.model.ScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortsTrackerManager @Inject constructor(
    private val detector: YouTubeShortsDetector
){

    private val _shortsCount = MutableStateFlow(0)

    val shortsCount: StateFlow<Int> =_shortsCount

    private var currentShortId : String? = null
    fun processAccessibilityEvent(rootNode: AccessibilityNodeInfo) {

        when (val state = detector.detect(rootNode)) {
            is ScreenState.WatchingShort -> {
                if (state.uniqueId != currentShortId) {
                    currentShortId = state.uniqueId
                    _shortsCount.value += 1
                    Log.d("ShortsTracker", ">>> NEW SHORT DETECTED! Total: ${_shortsCount.value} | ID: $currentShortId")
                }
            }
            is ScreenState.BrowsingFeed -> {
                // Only reset if we are DEFINITELY in the feed.
                if (currentShortId != null) {
                    Log.d("ShortsTracker", "Session Ended (Browsing Feed)")
                    currentShortId = null
                }
            }
            is ScreenState.Unknown -> {
                // CRITICAL: Do nothing. Keep the previous state active.
                // This handles the 500ms where "Remix" button might be loading.
                Log.d("ShortsTracker", "State: UNKNOWN - Heuristics failed to match anything.")
            }
        }
    }
}
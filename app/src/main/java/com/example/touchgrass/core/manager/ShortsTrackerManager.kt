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
    fun processAccessibilityEvent(rootNode : AccessibilityNodeInfo){
        Log.d("ShortsTracker", "working ")

        when(val state = detector.detect(rootNode)){
            is ScreenState.WatchingShort -> {
                if(state.uniqueId!= currentShortId){
                    currentShortId = state.uniqueId
                    _shortsCount.value += 1

                    Log.d("ShortsTracker", "New Short Detected! Count: ${_shortsCount.value} | ID: ${state.uniqueId}")
                }
            }
            is ScreenState.BrowsingFeed -> {
                // User left the player. Reset counting state if needed,
                // or just wait until they enter a new short.
                Log.d("ShortsTracker", "BrowsingFeed ")
                currentShortId = null

            }
            is ScreenState.Unknown -> {
                // Do nothing
                Log.d("ShortsTracker", "Unknown")

            }
        }
    }
}
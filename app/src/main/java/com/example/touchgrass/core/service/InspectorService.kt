package com.example.touchgrass.core.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.touchgrass.core.analyzer.NodeTreeAnalyzer
import com.example.touchgrass.core.manager.ShortsTrackerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// core/service/InspectorService.kt
@AndroidEntryPoint
class InspectorService : AccessibilityService() {

    @Inject
    lateinit var nodeAnalyzer: NodeTreeAnalyzer

    // We use a dedicated scope to avoid blocking the main thread during analysis
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Inject
    lateinit var trackerManager: ShortsTrackerManager
    private var lastAnalysisTime = 0L

    companion object {
        private const val ANALYSIS_COOLDOWN_MS = 500L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.tag("ShortsTracker").i("Service Connected & Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. FILTER: Only look at relevant events to save battery
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return
        }

        // 2. FILTER: Only look at target apps
        val packageName = event.packageName?.toString() ?: return
        if (packageName != "com.google.android.youtube" && packageName != "com.instagram.android") {
            return
        }

        // 3. ANALYZE: Get the root node
        val rootNode = rootInActiveWindow ?: return

        // 4. EXECUTE: Run analysis (Debounce could be added here later)
        serviceScope.launch {
            val logOutput = nodeAnalyzer.logNodeHierarchy(rootNode, packageName)
            Timber.tag("UI_TREE").d(logOutput)
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < ANALYSIS_COOLDOWN_MS) {
            return
        }
        lastAnalysisTime = currentTime
//        val rootNode = rootInActiveWindow ?: return
        serviceScope.launch {
            try {
                trackerManager.processAccessibilityEvent(rootNode)
            } catch (e: Exception) {
                Timber.tag("ShortsTracker").e(e, "Error processing accessibility event")
            }
        }

    }

    override fun onInterrupt() {
        Timber.w("Inspector Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Timber.tag("ShortsTracker").i("Service Destroyed")
    }
}
package com.example.touchgrass.core.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.touchgrass.core.analyzer.NodeTreeAnalyzer
import com.example.touchgrass.features.focus.FocusSessionManager
import com.example.touchgrass.core.manager.ShortsTrackerManager
import com.example.touchgrass.core.notifications.Notifier
import com.example.touchgrass.core.notifications.ServiceReminderWorker
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.features.github.GitHubGoalManager
import com.example.touchgrass.presentation.blockerView.BlockerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.jvm.java

// core/service/InspectorService.kt
@AndroidEntryPoint
class InspectorService : AccessibilityService() {

    @Inject
    lateinit var nodeAnalyzer: NodeTreeAnalyzer

    // We use a dedicated scope to avoid blocking the main thread during analysis
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Inject
    lateinit var trackerManager: ShortsTrackerManager

    @Inject
    lateinit var screenTimeNudger: ScreenTimeNudger

    @Inject
    lateinit var gitHubGoalManager: GitHubGoalManager

    @Inject
    lateinit var notifier: Notifier

    @Inject
    lateinit var focusSessionManager: FocusSessionManager

    private var lastAnalysisTime = 0L
    private var lastGoalLockAt = 0L
    private var lastFocusBlockAt = 0L

    companion object {
        private const val ANALYSIS_COOLDOWN_MS = 500L
        private const val GOAL_LOCK_COOLDOWN_MS = 3_000L
        private const val FOCUS_BLOCK_COOLDOWN_MS = 2_000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.tag("ShortsTracker").i("Service Connected & Ready")

        // Tracking is back on — end the "tracking off" reminder loop.
        ServiceReminderWorker.stop(this)
        notifier.cancel(Notifier.Ids.ACCESSIBILITY_OFF)

        serviceScope.launch {
            trackerManager.triggerBlockEvent.collect {
                triggerHardBlock()
            }
        }

    }

    private fun triggerHardBlock() {
        // Prepare the intent to launch the BlockerActivity
        val intent = Intent(this, BlockerActivity::class.java).apply {
            // Required flags when starting an Activity from outside an Activity context
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: If permission fails, we just force the Home button
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. FILTER
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return
        }

        // 2. PACKAGE CHECK
        val packageName = event.packageName?.toString() ?: return

        // Focus session: bounce blocked apps during a focus block (any package).
        if (focusSessionManager.shouldBlock(packageName)) {
            focusSessionManager.registerViolation()
            val now = System.currentTimeMillis()
            if (now - lastFocusBlockAt >= FOCUS_BLOCK_COOLDOWN_MS) {
                lastFocusBlockAt = now
                Toast.makeText(this, "Focus mode — stay on task.", Toast.LENGTH_SHORT).show()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (packageName !in ScreenTimeNudger.WATCHED_PACKAGES) {
            return
        }

        // Piggyback the screen-time nudge check on the event stream (self-throttled)
        screenTimeNudger.maybeNudge()

        // Force-read mode: while a reading gate is owed, watched apps stay blocked
        screenTimeNudger.enforceGate()

        // Goal lock: no entertainment until today's commit is done. Applies to
        // every watched app (YouTube, Instagram, Netflix).
        if (gitHubGoalManager.entertainmentLocked.value) {
            val now = System.currentTimeMillis()
            if (now - lastGoalLockAt >= GOAL_LOCK_COOLDOWN_MS) {
                lastGoalLockAt = now
                Toast.makeText(
                    this,
                    "Locked — finish today's goals to unlock entertainment.",
                    Toast.LENGTH_SHORT
                ).show()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        // Netflix is watch-time-only; no Shorts UI to analyze there
        if (packageName == "com.netflix.mediaclient") {
            return
        }

        // 3. THROTTLE
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < ANALYSIS_COOLDOWN_MS) {
            return
        }
        lastAnalysisTime = currentTime

        // 4. CAPTURE
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Timber.tag("ShortsTracker").w("rootNode was NULL")
            return
        }

        // 5. EXECUTE (DIRECTLY - NO COROUTINE)
        // We run this directly to ensure 'rootNode' is valid while we read it.
        try {
            trackerManager.processAccessibilityEvent(rootNode)
        } catch (e: Exception) {
            Timber.tag("ShortsTracker").e(e, "Error processing event")
        } finally {
            // It is good practice to recycle if you aren't using a coroutine
            // rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Timber.w("Inspector Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Service switched off (or unbound) — start the recurring reminder as soon
        // as it goes off, independent of opening the app.
        ServiceReminderWorker.start(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // Backstop in case onUnbind didn't fire. If the system rebinds, onServiceConnected
        // cancels this again (self-correcting), so a transient restart won't spam.
        ServiceReminderWorker.start(this)
        Timber.tag("ShortsTracker").i("Service Destroyed")
    }
}
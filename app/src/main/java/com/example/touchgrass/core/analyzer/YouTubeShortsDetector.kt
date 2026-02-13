package com.example.touchgrass.core.analyzer

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.model.ScreenState
import timber.log.Timber
import javax.inject.Inject

class YouTubeShortsDetector @Inject constructor() {

    var nodeAnalyzer: NodeTreeAnalyzer = NodeTreeAnalyzer()

    fun detect(rootNode: AccessibilityNodeInfo): ScreenState {

        // 1. GUARD: Ignore Normal Video Player
        // If we see "Enter fullscreen", it is DEFINITELY a long-form video. Abort.
        Log.d("ShortsTracker", "checking")

        if (isNormalVideoPlayer(rootNode)) {
            // Timber.d("ShortsDetector: Detected Normal Player (Ignored)")
            return ScreenState.BrowsingFeed
        }

        // 2. POSITIVE MATCH: Check for relaxed Shorts Fingerprints
        // We removed "Remix" because it's inconsistent.
        if (hasShortsFingerprints(rootNode)) {
            val uniqueId = extractVideoSignature(rootNode)

            return if (uniqueId.isNotEmpty()) {
                // Timber.d("ShortsDetector: Detected Short -> WATCHING ($uniqueId)")
                ScreenState.WatchingShort(uniqueId = uniqueId)
            } else {
                // We see the UI (Dislike btn), but text isn't ready. Keep waiting.
                // Timber.d("ShortsDetector: Detected Short UI (No ID yet)")
                ScreenState.Unknown
            }
        }

        // 3. Fallback
        // If we don't see "Dislike", we assume we are just browsing/scrolling.
        return ScreenState.BrowsingFeed
    }

    private fun hasShortsFingerprints(root: AccessibilityNodeInfo): Boolean {
        // We relax the rule: If we see "Dislike this video", it's a video.
        // Since we already excluded Normal Player (Step 1), this is likely a Short.
        Log.d("ShortsTracker", "inside hasShortsFingerprints new")

        val logOutput = nodeAnalyzer.logNodeHierarchy(root, "")
        Timber.tag("UI_TREE").d(logOutput)
        val dislikeNodes = root.findAccessibilityNodeInfosByText("Dislike this video")

        // Extra safety: Ensure we also see "Subscribe" to confirm it's a valid video container
        // and not just a stray button.
        val subscribeNodes = root.findAccessibilityNodeInfosByText("Subscribe")
        val result = dislikeNodes.isNotEmpty() && subscribeNodes.isNotEmpty()
        Log.d("ShortsTracker", "inside hasShortsFingerprints $result")
        return result
    }

    private fun isNormalVideoPlayer(root: AccessibilityNodeInfo): Boolean {
        // "Enter fullscreen" is the most reliable difference. Shorts don't have it.
        Log.d("ShortsTracker", "inside isNormalVideoPlayer")

        return root.findAccessibilityNodeInfosByText("Enter fullscreen").isNotEmpty()
    }

    private fun extractVideoSignature(root: AccessibilityNodeInfo): String {
        // Use "Subscribe to @ChannelName" as the ID.
        // It's the most stable text element in your logs.
        val subscribeNodes = root.findAccessibilityNodeInfosByText("Subscribe to")
        if (subscribeNodes.isNotEmpty()) {
            return subscribeNodes[0].contentDescription?.toString() ?: ""
        }

        // Fallback: Try finding the channel name button directly if "Subscribe to" phrasing changes
        // (This part requires finding the node with text "@ChannelName", but let's stick to the log evidence first)

        return ""
    }
}
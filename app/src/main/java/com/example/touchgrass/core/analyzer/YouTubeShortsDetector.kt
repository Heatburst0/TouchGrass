package com.example.touchgrass.core.analyzer

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.model.ScreenState
import timber.log.Timber
import javax.inject.Inject

class YouTubeShortsDetector @Inject constructor() {
    var nodeAnalyzer: NodeTreeAnalyzer = NodeTreeAnalyzer()
    fun detect(rootNode: AccessibilityNodeInfo): ScreenState {
        // Run the Single Pass Analysis
        val analysis = scanTree(rootNode)

        // 1. Logic: Is it a Normal Video?
        if (analysis.hasEnterFullscreen) {
            // Log.d("ShortsDetector", "Found 'Enter fullscreen' -> Normal Video")
            return ScreenState.BrowsingFeed
        }

        // 2. Logic: Is it a Short?
        // We look for Dislike + Subscribe buttons
        if (analysis.hasDislikeButton && analysis.hasSubscribeButton) {

            // Log.d("ShortsDetector", "Found Shorts UI -> ID: ${analysis.subscribeDescription}")

            return if (analysis.subscribeDescription.isNotEmpty()) {
                ScreenState.WatchingShort(uniqueId = analysis.subscribeDescription)
            } else {
                ScreenState.Unknown // UI matches, but text not loaded yet
            }
        }

        return ScreenState.BrowsingFeed
    }

    // Data class to hold all our findings from the scan
    private data class TreeClues(
        var hasEnterFullscreen: Boolean = false,
        var hasDislikeButton: Boolean = false,
        var hasSubscribeButton: Boolean = false,
        var subscribeDescription: String = ""
    )

    /**
     * Traverses the entire node tree ONCE and fills in the clues.
     * This replaces all separate find...ByText calls.
     */
    private fun scanTree(root: AccessibilityNodeInfo): TreeClues {
        val clues = TreeClues()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        Log.d("ShortsTracker", "inside hasShortsFingerprints new")
        val logOutput = nodeAnalyzer.logNodeHierarchy(root, "")
        Timber.tag("UI_TREE").d(logOutput)

        var nodesChecked = 0
        // Safety limit to prevent hanging on massive UI trees
        val maxNodes = 500

        while (stack.isNotEmpty() && nodesChecked < maxNodes) {
            val node = stack.removeLast()
            nodesChecked++

            // Safe property access
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val className = node.className?.toString() ?: ""

            // --- CHECK 1: Normal Video Fingerprint ---
            if (desc == "Enter fullscreen" || text == "Enter fullscreen") {
                clues.hasEnterFullscreen = true
            }

            // --- CHECK 2: Shorts Fingerprint (Dislike) ---
            // Your logs showed this is a RadioButton in Shorts
            if (desc == "Dislike this video" && className == "android.widget.RadioButton") {
                clues.hasDislikeButton = true
            }

            // --- CHECK 3: Shorts Fingerprint (Subscribe) ---
            if (desc.contains("Subscribe", ignoreCase = true) || text.contains("Subscribe", ignoreCase = true)) {
                clues.hasSubscribeButton = true

                // If this is the specific "Subscribe to @Name" button, capture it as the ID
                if (desc.startsWith("Subscribe to")) {
                    clues.subscribeDescription = desc
                }
            }

            // Optimization: If we found everything, we can stop early!
            if (clues.hasEnterFullscreen || (clues.hasDislikeButton && clues.subscribeDescription.isNotEmpty())) {
                return clues
            }

            // Add children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return clues
    }
}
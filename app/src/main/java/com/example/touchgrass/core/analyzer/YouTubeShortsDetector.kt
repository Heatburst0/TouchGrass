package com.example.touchgrass.core.analyzer

import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.model.ScreenState
import javax.inject.Inject

class YouTubeShortsDetector @Inject constructor() {

    // Matches the spoken text of a video timeline: "0 minutes 5 seconds of..."
    private val timeRegex = Regex(".*minutes.*seconds.*", RegexOption.IGNORE_CASE)

    fun detect(rootNode: AccessibilityNodeInfo): ScreenState {
        val analysis = scanTree(rootNode)

        // 1. GUARD: Is it a Normal Video?
        // Normal videos have a fullscreen toggle. Shorts do not.
        if (analysis.hasEnterFullscreen) {
            return ScreenState.BrowsingFeed
        }

        // 2. LOGIC: Is it a Short?
        // We no longer look for Like/Dislike. We look for the literal video progress bar!
        if (analysis.hasVideoProgressBar) {
            // Per-VIDEO fingerprint: channel + total duration. Stable across
            // pause / comments / tab-switch, yet different between two shorts from
            // the same channel (which the old channel-only id could not tell apart).
            val fingerprint = analysis.channelSignature + "|" + analysis.videoDuration
            return if (fingerprint == "|") ScreenState.Unknown
                   else ScreenState.WatchingShort(uniqueId = fingerprint)
        }

        return ScreenState.BrowsingFeed

    }

    private data class TreeClues(
        var hasEnterFullscreen: Boolean = false,
        var hasVideoProgressBar: Boolean = false,
        var channelSignature: String = "",
        var videoDuration: String = ""
    )


    private fun scanTree(root: AccessibilityNodeInfo): TreeClues {
        val clues = TreeClues()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        var nodesChecked = 0
        val maxNodes = 500

        while (stack.isNotEmpty() && nodesChecked < maxNodes) {
            val node = stack.removeLast()
            nodesChecked++

            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val className = node.className?.toString() ?: ""

            // --- CHECK 1: Normal Video Fingerprint ---
            if (desc == "Enter fullscreen" || text == "Enter fullscreen") {
                clues.hasEnterFullscreen = true
            }

            // --- CHECK 2: The Structural Video Player Fingerprint ---
            // Instead of looking for fragile UI buttons, we look for the video seekbar itself.
            // Your logs: [SeekBar] Desc: 0 minutes 2 seconds of 0 minutes 58 seconds
            if (className == "android.widget.SeekBar" && timeRegex.matches(desc)) {
                clues.hasVideoProgressBar = true
                // "0 minutes 2 seconds of 0 minutes 58 seconds" -> "0 minutes 58 seconds".
                // The TOTAL length is stable per video; the current position isn't.
                clues.videoDuration = desc.substringAfter(" of ", "").trim()
            }


            // --- CHECK 3: The Channel Signature (For Unique ID) ---
            // YouTube sometimes uses "Subscribe to @Name" or "Go to channel @Name"
            if (desc.startsWith("Subscribe to @", ignoreCase = true) ||
                desc.startsWith("Go to channel @", ignoreCase = true)) {
                clues.channelSignature = desc
            }

            // --- OPTIMIZATION: Early Exit ---
            // Only bail early once we KNOW it's a normal video (fullscreen toggle).
            // Otherwise keep scanning so we read BOTH the seekbar (duration) and the
            // channel before deciding — a normal video's seekbar must not short-circuit
            // us into a "Short" verdict before its fullscreen button is found.
            if (clues.hasEnterFullscreen) {
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
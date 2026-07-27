package com.example.touchgrass.core.analyzer

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.model.ScreenState
import javax.inject.Inject

class YouTubeShortsDetector @Inject constructor() {

    private var lastTreeLogAt = 0L

    fun detect(rootNode: AccessibilityNodeInfo): ScreenState {
        val c = scanTree(rootNode)

        // Normal videos expose a fullscreen toggle; Shorts never do.
        if (c.hasEnterFullscreen) return ScreenState.BrowsingFeed

        // POSITIVE Shorts signal: the Shorts player has a vertical action rail
        // (like / comment / share / remix) and a "Video Progress" bar. Home-feed
        // autoplay previews and the normal player have none of these, so requiring
        // one is what separates a real Short from an autoplaying feed video —
        // without depending on the (intermittent) seekbar or the
        // (subscribe-state-dependent) channel label.
        if (c.hasShortsMarker) {
            // Per-video fingerprint from stable rail values, so two shorts from the
            // SAME channel still count separately (their like/comment counts differ),
            // while pause / comments / tab-switch keep the same fingerprint.
            val fingerprint = listOf(c.channelSignature, c.likeSignature, c.commentSignature)
                .joinToString("|")
            return if (fingerprint == "||") ScreenState.Unknown
                   else ScreenState.WatchingShort(uniqueId = fingerprint)
        }

        return ScreenState.BrowsingFeed
    }

    private data class TreeClues(
        var hasEnterFullscreen: Boolean = false,
        var hasShortsMarker: Boolean = false,
        var channelSignature: String = "",
        var likeSignature: String = "",
        var commentSignature: String = ""
    )

    private fun scanTree(root: AccessibilityNodeInfo): TreeClues {
        val clues = TreeClues()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        // Throttled full-tree dump for future detector tuning. Flip DEBUG_TREE on
        // to capture; at most one dump per LOG_THROTTLE_MS so logcat stays readable.
        val logging = DEBUG_TREE && System.currentTimeMillis() - lastTreeLogAt > LOG_THROTTLE_MS
        if (logging) {
            lastTreeLogAt = System.currentTimeMillis()
            Log.d(LOG_TAG, "===================== UI TREE DUMP =====================")
        }

        var nodesChecked = 0
        val maxNodes = if (logging) 2000 else 500

        while (stack.isNotEmpty() && nodesChecked < maxNodes) {
            val node = stack.removeLast()
            nodesChecked++

            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val className = node.className?.toString() ?: ""

            if (logging) Log.d(LOG_TAG, "$className | desc='$desc' | text='$text'")

            // Normal-video fingerprint: fullscreen toggle.
            if (desc == "Enter fullscreen" || text == "Enter fullscreen") {
                clues.hasEnterFullscreen = true
            }

            // Shorts-ONLY markers. Deliberately NOT "like this video" / "Share":
            // the home-feed inline autoplay of a REGULAR video also shows those
            // (that was the false positive). Only the real Shorts player has a
            // "Video Progress" element (the feed uses a SeekBar) and "Remix".
            if (desc == "Video Progress") clues.hasShortsMarker = true
            if (desc == "Remix" || desc.startsWith("Remix this Short", ignoreCase = true)) {
                clues.hasShortsMarker = true
            }
            // Captured for the fingerprint only — NOT a marker on its own.
            if (desc.startsWith("like this video", ignoreCase = true)) {
                clues.likeSignature = desc          // "...along with 58 thousand other people"
            }

            // Fingerprint helpers (not markers on their own).
            if (desc.startsWith("View ", ignoreCase = true) &&
                desc.contains("comment", ignoreCase = true)
            ) {
                clues.commentSignature = desc       // "View 482 comments"
            }
            if (desc.startsWith("Subscribe to @", ignoreCase = true) ||
                desc.startsWith("Go to channel @", ignoreCase = true)
            ) {
                clues.channelSignature = desc
            }

            // Stop early only once we're certain it's a normal video.
            if (!logging && clues.hasEnterFullscreen) return clues

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return clues
    }

    private companion object {
        const val DEBUG_TREE = false         // flip true to dump the UI tree to logcat
        const val LOG_TAG = "UI_TREE"
        const val LOG_THROTTLE_MS = 1500L
    }
}

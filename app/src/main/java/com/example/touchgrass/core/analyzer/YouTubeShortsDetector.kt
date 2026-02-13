package com.example.touchgrass.core.analyzer

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.touchgrass.core.model.ScreenState
import javax.inject.Inject

class YouTubeShortsDetector @Inject constructor() {

    fun detect(rootNode: AccessibilityNodeInfo) : ScreenState {

        if(isNormalVideoPlayer(rootNode)) {
            Log.d("ShortsDetector", "Detected as NORMAL VIDEO (Ignored)")
            return ScreenState.BrowsingFeed
        }

        val isShortUI = hasShortsFingerprints(rootNode)

        if(isShortUI){
            Log.d("ShortsDetector", "Shorts Fingerprints FOUND")
            val uniqueId : String= extractVideoSignature(rootNode)
            return if(uniqueId.isNotEmpty()){
                ScreenState.WatchingShort(
                    uniqueId = uniqueId
                )
            }else{
                Log.d("ShortsDetector", "Shorts Fingerprints NOT found")
                ScreenState.Unknown
            }
        }
        if (isMainNavigationVisible(rootNode)) {
            Log.d("ShortsDetector", "Detected as NORMAL VIDEO (Ignored)")
            return ScreenState.BrowsingFeed
        }

        // 4. Fallback: If it's none of the above, it's a glitch/transition.
        // Return Unknown so the Manager ignores it.
        return ScreenState.Unknown
    }

    private fun extractVideoSignature(rootNode: AccessibilityNodeInfo): String {
        val subscribeNodes =  rootNode.findAccessibilityNodeInfosByText("Subscribe to")

        if(subscribeNodes.isNotEmpty()){
            return subscribeNodes[0].contentDescription?.toString() ?: ""
        }
        return ""
    }

    private fun hasShortsFingerprints(rootNode: AccessibilityNodeInfo): Boolean {

        val remixNodes = rootNode.findAccessibilityNodeInfosByText("Remix")
        val isStrictRemix = remixNodes.any {
            it.contentDescription?.toString() == "Remix"
        }
        val dislikeNodes = rootNode.findAccessibilityNodeInfosByText("Dislike this video")

        return isStrictRemix && dislikeNodes.isNotEmpty()
    }

    private fun isNormalVideoPlayer(root: AccessibilityNodeInfo): Boolean{
        val hasFullscreenBtn = root.findAccessibilityNodeInfosByText("Enter fullscreen").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText("Exit fullscreen").isNotEmpty()

        // "Captions" button (CC) is usually prominent in the main player controls
        // but handled differently or hidden in menus in Shorts.
//        val hasCaptionsBtn = root.findAccessibilityNodeInfosByText("Captions").isNotEmpty()

        // "Next video" (Skip track icon) exists in normal player controls.
        // In Shorts, you swipe, you don't click a "Next" button.
        val hasNextBtn = root.findAccessibilityNodeInfosByText("Next video").isNotEmpty()

        return hasFullscreenBtn  || hasNextBtn
    }
    private fun isMainNavigationVisible(root: AccessibilityNodeInfo): Boolean {
        // The bottom nav always has these buttons
        val hasHome = root.findAccessibilityNodeInfosByText("Home").isNotEmpty()
        val hasSubs = root.findAccessibilityNodeInfosByText("Subscriptions").isNotEmpty()
        val hasYou = root.findAccessibilityNodeInfosByText("You").isNotEmpty() // "You" tab

        // If we see at least 2 of these, it's definitely the main feed
        return (hasHome && hasSubs) || (hasHome && hasYou)
    }
}
package com.example.touchgrass.core.model

sealed class ScreenState {
    data object Unknown : ScreenState()
    data object BrowsingFeed : ScreenState()
    data class WatchingShort(
        val uniqueId : String,
        val platform: String ="Youtube"
    ) : ScreenState()
}
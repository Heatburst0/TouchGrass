package com.example.touchgrass.presentation.dashboard

import androidx.lifecycle.ViewModel
import com.example.touchgrass.core.manager.ShortsStats
import com.example.touchgrass.core.manager.ShortsTrackerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val trackerManager: ShortsTrackerManager
) : ViewModel() {
    // Expose live state directly from the singleton manager
    val stats: StateFlow<ShortsStats> = trackerManager.stats
    val shortsLimit: StateFlow<Int> = trackerManager.shortsLimit

    fun updateShortsLimit(newLimit: Int) {
        trackerManager.updateShortsLimit(newLimit)
    }
}

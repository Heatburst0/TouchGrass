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
    // Expose the stats directly from the singleton manager
    val stats: StateFlow<ShortsStats> = trackerManager.stats

    fun updateShortsLimit(newLimit: Int) {
        trackerManager.shortsLimit = newLimit
    }
}
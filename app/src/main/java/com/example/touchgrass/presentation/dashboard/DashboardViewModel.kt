package com.example.touchgrass.presentation.dashboard

import androidx.lifecycle.ViewModel
import com.example.touchgrass.core.manager.ShortsStats
import com.example.touchgrass.core.manager.ShortsTrackerManager
import com.example.touchgrass.core.rewards.RewardsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val trackerManager: ShortsTrackerManager,
    rewards: RewardsManager
) : ViewModel() {
    val stats: StateFlow<ShortsStats> = trackerManager.stats

    /** Base limit the user configures. */
    val shortsLimit: StateFlow<Int> = trackerManager.shortsLimit

    /** Base + shorts earned back by reading; what the ring and blocker use. */
    val effectiveLimit: StateFlow<Int> = trackerManager.effectiveLimit
    val extraShortsToday: StateFlow<Int> = rewards.extraShortsToday
    val penaltyShortsToday: StateFlow<Int> = rewards.penaltyShortsToday
    val pointsBalance: StateFlow<Int> = rewards.pointsBalance

    fun updateShortsLimit(newLimit: Int) {
        trackerManager.updateShortsLimit(newLimit)
    }
}

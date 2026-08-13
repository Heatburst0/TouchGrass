package com.example.touchgrass.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.db.CommitmentEntity
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.core.manager.ShortsStats
import com.example.touchgrass.core.manager.ShortsTrackerManager
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.github.GitHubGoalManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val trackerManager: ShortsTrackerManager,
    rewards: RewardsManager,
    goalEngine: GoalEngine,
    gitHubManager: GitHubGoalManager
) : ViewModel() {
    val stats: StateFlow<ShortsStats> = trackerManager.stats

    /** Base limit the user configures. */
    val shortsLimit: StateFlow<Int> = trackerManager.shortsLimit

    /** Base + shorts earned back by reading; what the ring and blocker use. */
    val effectiveLimit: StateFlow<Int> = trackerManager.effectiveLimit
    val extraShortsToday: StateFlow<Int> = rewards.extraShortsToday
    val penaltyShortsToday: StateFlow<Int> = rewards.penaltyShortsToday
    val pointsBalance: StateFlow<Int> = rewards.pointsBalance

    /** Ongoing goals surfaced on the home screen. */
    val activeCommitments: StateFlow<List<CommitmentEntity>> = goalEngine.activeCommitments
    val gitHubGoals: StateFlow<List<GitHubGoalEntity>> = gitHubManager.goals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Detector test mode: tracked-count without touching the real limit. */
    val testMode: StateFlow<Boolean> = trackerManager.testMode
    val testCount: StateFlow<Int> = trackerManager.testCount

    fun updateShortsLimit(newLimit: Int) {
        trackerManager.updateShortsLimit(newLimit)
    }

    fun setTestMode(enabled: Boolean) {
        trackerManager.setTestMode(enabled)
    }
}

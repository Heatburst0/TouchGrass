package com.example.touchgrass.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.db.CommitmentEntity
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.core.manager.ShortsStats
import com.example.touchgrass.core.manager.ShortsTrackerManager
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.core.discipline.PlanField
import com.example.touchgrass.features.github.GitHubGoalManager
import com.example.touchgrass.features.weeklyplan.WeeklyPlanRepository
import com.example.touchgrass.features.weeklyplan.WeeklyPlanState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val trackerManager: ShortsTrackerManager,
    rewards: RewardsManager,
    goalEngine: GoalEngine,
    gitHubManager: GitHubGoalManager,
    private val weeklyPlanRepo: WeeklyPlanRepository
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

    /** Weekly plan lock — drives the lock badge next to the Shorts limit. */
    val weeklyPlan: StateFlow<WeeklyPlanState> = weeklyPlanRepo.state

    /** Refused changes (loosening while the weekly plan is locked) for a snackbar. */
    val lockMessage = MutableStateFlow<String?>(null)

    /** Goes through the weekly plan: lowering always works; raising waits for the weekend. */
    fun updateShortsLimit(newLimit: Int) {
        viewModelScope.launch {
            val result = weeklyPlanRepo.update { it.copy(shortsDailyLimit = newLimit) }
            if (PlanField.SHORTS_LIMIT in result.refused) {
                lockMessage.value = "Locked by your weekly plan — you can lower it, not raise it."
            }
        }
    }

    /** Test mode stops real counting and blocking, so it can't be turned on while locked. */
    fun setTestMode(enabled: Boolean) {
        if (enabled && weeklyPlanRepo.currentState().locked) {
            lockMessage.value = "Test mode is off while your weekly plan is locked."
            return
        }
        trackerManager.setTestMode(enabled)
    }
}

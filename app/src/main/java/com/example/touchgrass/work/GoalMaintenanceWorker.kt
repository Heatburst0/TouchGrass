package com.example.touchgrass.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.features.github.GitHubGoalManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Periodic background maintenance for every goal — replaces the GitHub-only
 * worker. Each run:
 *  - polls GitHub goals (until Option B turns GitHub into a pure Polled verifier),
 *  - settles one-shot pledges whose deadline elapsed,
 *  - settles recurring goals across elapsed periods (streaks + per-period penalty),
 *    so a daily/weekly miss registers even when the app is never opened that day.
 *
 * End state: the GitHub call becomes GoalOrchestrator.runPolled(), and any future
 * API-backed goal is picked up here for free — no new worker per feature.
 */
@HiltWorker
class GoalMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val goalEngine: GoalEngine,
    private val gitHubGoalManager: GitHubGoalManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        gitHubGoalManager.runChecks()
        goalEngine.settleOverdue()
        goalEngine.settleRecurring()
        Result.success()
    } catch (e: Exception) {
        Timber.tag("Goals").w(e, "Maintenance run failed; will retry")
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "goal_maintenance"
    }
}

package com.example.touchgrass.features.github

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Background daily verification. Runs on a periodic schedule (and on app open)
 * so a missed commit is punished even on days the user never opens the app.
 */
@HiltWorker
class GitHubCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: GitHubGoalManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            manager.runChecks()
            Result.success()
        } catch (e: Exception) {
            Timber.tag("GitHub").w(e, "Worker run failed; will retry")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "github_daily_check"
    }
}

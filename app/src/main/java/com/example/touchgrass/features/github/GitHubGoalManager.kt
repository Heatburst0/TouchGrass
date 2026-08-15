package com.example.touchgrass.features.github

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.GitHubGoalDao
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.data.db.GoalDao
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recurring GitHub daily-commit goals. As of the Goal+Verifier refactor (Phase 1)
 * this is a thin facade over the unified `goals` table: storage and verification
 * live in [GitHubVerifier], while this class keeps the exact public surface the
 * rest of the app already depends on (UI list, entertainment lock, add/remove,
 * background check) so no consumer had to change.
 *
 * Fairness rule (unchanged): a MISS is only recorded when the API definitively
 * answers "no commits that day"; any network/API error aborts that goal's run
 * and is retried later.
 */
@Singleton
class GitHubGoalManager @Inject constructor(
    private val goalDao: GoalDao,
    private val legacyDao: GitHubGoalDao,      // one-time migration of pre-refactor rows
    private val verifier: GitHubVerifier,
    private val api: GitHubApi,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** GITHUB_COMMIT goals, mapped to the DTO the existing UI renders. */
    val goals: Flow<List<GitHubGoalEntity>> = goalDao.observeAll().map { all ->
        all.filter { it.type == GoalType.GITHUB_COMMIT.name }
            .map { it.toGitHubGoalEntity() }
    }

    /**
     * True when the goal-lock is on AND at least one active repo hasn't been
     * committed to today — i.e., entertainment should be blocked. Read
     * synchronously via `.value` from the accessibility service.
     */
    val entertainmentLocked: StateFlow<Boolean> =
        combine(
            settings.goalLockEnabled,
            goalDao.observeAll()
        ) { enabled, allGoals ->
            if (!enabled) return@combine false
            val today = LocalDate.now().toString()
            val gitHubOwed = allGoals.any {
                it.active && it.type == GoalType.GITHUB_COMMIT.name &&
                    it.gitHubState().lastSuccessDate != today
            }
            // Only pledges DUE today (or overdue-but-unsettled) lock — a goal due
            // in 3 days shouldn't block entertainment until its deadline is near.
            val startOfTomorrow = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pledgeOwed = allGoals.any {
                it.active && it.type == GoalType.TASK.name &&
                    (it.deadlineAt ?: Long.MAX_VALUE) < startOfTomorrow
            }
            gitHubOwed || pledgeOwed
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Move any pre-refactor github_goals rows into the unified table, once.
        scope.launch { migrateLegacyGoals() }
    }

    /**
     * Validates the repo is reachable BEFORE saving. Returns null on success,
     * or a user-facing error message (e.g. a private repo that needs a token).
     */
    suspend fun addGoal(owner: String, repo: String, author: String): String? {
        val token = settings.githubToken.first().ifBlank { null }
        return try {
            // Throwaway reachability probe: if GitHub answers at all, the repo
            // exists and we can read it. 404/401/403 arrive as GitHubException.
            api.hasCommit(
                owner, repo, author.ifBlank { null },
                since = Instant.now().minusSeconds(86_400),
                until = Instant.now(),
                token = token
            )
            goalDao.upsert(
                newGitHubGoal(owner, repo, author, DAILY_REWARD_POINTS, DAILY_PENALTY_SHORTS, System.currentTimeMillis())
            )
            null
        } catch (e: GitHubException) {
            e.message
        } catch (e: IOException) {
            "No connection — check your internet and try again."
        }
    }

    suspend fun removeGoal(id: Long) = goalDao.delete(id)

    /** Poll + settle every active goal via the verifier. Safe to call often. */
    suspend fun runChecks() {
        goalDao.activeOfType(GoalType.GITHUB_COMMIT.name).forEach { goal ->
            try {
                verifier.verify(goal)
            } catch (e: Exception) {
                // Network/API/rate-limit failure — leave state untouched, retry next run.
                Timber.tag("GitHub").w(e, "Check failed for goal %d", goal.id)
            }
        }
    }

    private suspend fun migrateLegacyGoals() {
        if (settings.githubGoalsMigrated.first()) return
        val legacy = legacyDao.activeGoals()
        val now = System.currentTimeMillis()
        legacy.forEach { goalDao.upsert(it.toGoalEntity(now)) }
        settings.setGithubGoalsMigrated(true)
        if (legacy.isNotEmpty()) {
            Timber.tag("GitHub").i("Migrated %d legacy goal(s) into the goals table", legacy.size)
        }
    }

    companion object {
        const val DAILY_REWARD_POINTS = 30   // ~3 pages' worth for a daily commit
        const val DAILY_PENALTY_SHORTS = 5   // docked from today's allowance on a miss
    }
}

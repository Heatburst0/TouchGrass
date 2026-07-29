package com.example.touchgrass.features.github

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.CommitmentDao
import com.example.touchgrass.core.data.db.GitHubGoalDao
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recurring GitHub daily-commit goals. Verification is a background poll of the
 * GitHub API, so reward (commit today → points + streak) and punishment (a day
 * ends with no commit → screen time docked + streak reset) both flow through
 * [RewardsManager], the same economy every other pillar uses.
 *
 * Fairness rule: a MISS is only recorded when the API definitively answers
 * "no commits that day". Any network/API error aborts the run for that goal and
 * is retried later — we never punish for our own failure to check.
 */
@Singleton
class GitHubGoalManager @Inject constructor(
    private val dao: GitHubGoalDao,
    private val api: GitHubApi,
    private val rewards: RewardsManager,
    private val settings: SettingsRepository,
    private val commitmentDao: CommitmentDao,
    @ApplicationScope private val scope: CoroutineScope
) {

    val goals: Flow<List<GitHubGoalEntity>> = dao.observeAll()

    /**
     * True when the goal-lock is on AND at least one active repo hasn't been
     * committed to today — i.e., entertainment should be blocked. Read
     * synchronously via `.value` from the accessibility service.
     */
    val entertainmentLocked: StateFlow<Boolean> =
        combine(
            settings.goalLockEnabled,
            dao.observeAll(),
            commitmentDao.observeActive()
        ) { enabled, gitHubGoals, activeCommitments ->
            if (!enabled) return@combine false
            val today = LocalDate.now().toString()
            val gitHubOwed = gitHubGoals.any { it.active && it.lastSuccessDate != today }
            // Only pledges DUE today (or overdue-but-unsettled) lock — a goal due
            // in 3 days shouldn't block entertainment until its deadline is near.
            val startOfTomorrow = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pledgeOwed = activeCommitments.any { it.deadlineAt < startOfTomorrow }
            gitHubOwed || pledgeOwed
        }.stateIn(scope, SharingStarted.Eagerly, false)

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
            dao.insert(
                GitHubGoalEntity(
                    owner = owner.trim(),
                    repo = repo.trim(),
                    author = author.trim(),
                    createdDate = LocalDate.now().toString(),
                    lastSuccessDate = null,
                    lastSettledDate = null,
                    currentStreak = 0,
                    bestStreak = 0,
                    rewardPoints = DAILY_REWARD_POINTS,
                    penaltyShorts = DAILY_PENALTY_SHORTS,
                    active = true
                )
            )
            null
        } catch (e: GitHubException) {
            e.message
        } catch (e: IOException) {
            "No connection — check your internet and try again."
        }
    }


    suspend fun removeGoal(id: Long) = dao.delete(id)

    /** Poll + settle every active goal. Safe to call often; self-limiting per day. */
    suspend fun runChecks() {
        val token = settings.githubToken.first().ifBlank { null }
        dao.activeGoals().forEach { goal ->
            try {
                checkGoal(goal, token)
            } catch (e: Exception) {
                // Network/API/rate-limit failure — leave state untouched, retry next run.
                Timber.tag("GitHub").w(e, "Check failed for %s/%s", goal.owner, goal.repo)
            }
        }
    }

    private suspend fun checkGoal(goal: GitHubGoalEntity, token: String?) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var g = goal

        // 1. Reward: has a commit landed today (and not yet credited)?
        if (g.lastSuccessDate != today.toString()) {
            val committed = api.hasCommit(
                g.owner, g.repo, g.author.ifBlank { null },
                since = today.atStartOfDay(zone).toInstant(),
                until = Instant.now(),
                token = token
            )
            if (committed) g = creditDay(g, today)
        }

        // 2. Punish: finalize each fully-elapsed day that wasn't a success.
        settleElapsedDays(g, today, zone, token)
    }

    private suspend fun creditDay(g: GitHubGoalEntity, day: LocalDate): GitHubGoalEntity {
        val continues = g.lastSuccessDate == day.minusDays(1).toString()
        val streak = if (continues) g.currentStreak + 1 else 1
        val updated = g.copy(
            lastSuccessDate = day.toString(),
            currentStreak = streak,
            bestStreak = maxOf(g.bestStreak, streak)
        )
        dao.update(updated)
        rewards.award(g.rewardPoints, "github_commit:${g.id}:$day")
        Timber.tag("GitHub").i("%s/%s committed %s (streak %d)", g.owner, g.repo, day, streak)
        return updated
    }

    private suspend fun settleElapsedDays(
        goal: GitHubGoalEntity,
        today: LocalDate,
        zone: ZoneId,
        token: String?
    ) {
        var g = goal
        val created = LocalDate.parse(g.createdDate)
        var day = (g.lastSettledDate?.let { LocalDate.parse(it).plusDays(1) } ?: created)
        if (day.isBefore(created)) day = created

        var guard = 0
        while (day.isBefore(today) && guard < MAX_BACKFILL_DAYS) {
            // Already credited live? Otherwise ask the API about that exact day.
            val success = g.lastSuccessDate == day.toString() || api.hasCommit(
                g.owner, g.repo, g.author.ifBlank { null },
                since = day.atStartOfDay(zone).toInstant(),
                until = day.plusDays(1).atStartOfDay(zone).toInstant(),
                token = token
            )
            g = if (!success) {
                rewards.applyPenaltyShorts(g.penaltyShorts)
                Timber.tag("GitHub").i("%s/%s MISSED %s (-%d shorts)", g.owner, g.repo, day, g.penaltyShorts)
                g.copy(currentStreak = 0, lastSettledDate = day.toString())
            } else {
                g.copy(lastSettledDate = day.toString())
            }
            dao.update(g)
            day = day.plusDays(1)
            guard++
        }
    }

    companion object {
        const val DAILY_REWARD_POINTS = 30   // ~3 pages' worth for a daily commit
        const val DAILY_PENALTY_SHORTS = 5   // docked from today's allowance on a miss
        private const val MAX_BACKFILL_DAYS = 14 // cap catch-up work per run
    }
}

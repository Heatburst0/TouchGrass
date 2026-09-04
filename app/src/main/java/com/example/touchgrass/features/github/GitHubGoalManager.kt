package com.example.touchgrass.features.github

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.data.db.GoalDao
import com.example.touchgrass.core.goals.GoalOrchestrator
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.goals.Recurrence
import com.example.touchgrass.core.goals.RecurrenceSchedule
import com.example.touchgrass.core.goals.metThisPeriod
import com.example.touchgrass.core.goals.recurrence
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
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub commit goals. As of Option B a GitHub goal is a RECURRING goal on the
 * shared engine (Daily by default, editable to Weekly/Custom) — the verifier just
 * reports "committed this period?" and [GoalOrchestrator] + GoalEngine.settleRecurring
 * own streaks/rewards/penalties. This class stays a thin facade over the goals
 * table so the UI, entertainment lock, and worker didn't change shape.
 */
@Singleton
class GitHubGoalManager @Inject constructor(
    private val goalDao: GoalDao,
    private val orchestrator: GoalOrchestrator,
    private val api: GitHubApi,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** GITHUB_COMMIT goals, mapped to the DTO the existing card renders. */
    val goals: Flow<List<GitHubGoalEntity>> = goalDao.observeAll().map { all ->
        all.filter { it.type == GoalType.GITHUB_COMMIT.name }
            .map { it.toGitHubGoalEntity() }
    }

    /**
     * True when the goal-lock is on AND at least one active goal owes work now.
     * A GitHub goal owes when TODAY is one of its active periods and it hasn't
     * been committed to yet this period (so a Mon–Fri goal doesn't lock weekends).
     */
    val entertainmentLocked: StateFlow<Boolean> =
        combine(settings.goalLockEnabled, goalDao.observeAll()) { enabled, allGoals ->
            if (!enabled) return@combine false
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val gitHubOwed = allGoals.any { g ->
                g.active && g.type == GoalType.GITHUB_COMMIT.name && !g.metThisPeriod() &&
                    RecurrenceSchedule.periodOn(g.recurrence(), today, zone) != null
            }
            // Pledges due today (or overdue-but-unsettled) also lock.
            val startOfTomorrow = today.plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val pledgeOwed = allGoals.any {
                it.active && it.type == GoalType.TASK.name &&
                    (it.deadlineAt ?: Long.MAX_VALUE) < startOfTomorrow
            }
            gitHubOwed || pledgeOwed
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch { migrateGitHubToRecurring() }
    }

    /**
     * Validates the repo is reachable BEFORE saving. Returns null on success, or a
     * user-facing error (e.g. a private repo that needs a token). [recurrence]
     * defaults to Daily but can be Weekly or Custom weekdays.
     */
    suspend fun addGoal(
        owner: String,
        repo: String,
        author: String,
        recurrence: Recurrence = Recurrence.Daily
    ): String? {
        val token = settings.githubToken.first().ifBlank { null }
        return try {
            api.hasCommit(
                owner, repo, author.ifBlank { null },
                since = Instant.now().minusSeconds(86_400),
                until = Instant.now(),
                token = token
            )
            goalDao.upsert(
                newGitHubGoal(
                    owner, repo, author,
                    DAILY_REWARD_POINTS, DAILY_PENALTY_SHORTS,
                    System.currentTimeMillis(), recurrence
                )
            )
            null
        } catch (e: GitHubException) {
            e.message
        } catch (e: IOException) {
            "No connection — check your internet and try again."
        }
    }

    suspend fun removeGoal(id: Long) = goalDao.delete(id)

    /** Poll GitHub goals through the shared spine — sets metThisPeriod when a commit
     *  lands this period. Streaks/rewards/penalties are settled by the engine. */
    suspend fun runChecks() = orchestrator.runPolled()

    /** Option B: reshape pre-recurring GITHUB_COMMIT rows into the recurring model. */
    private suspend fun migrateGitHubToRecurring() {
        if (settings.githubRecurringMigrated.first()) return
        val toReshape = goalDao.observeAll().first().filter {
            it.type == GoalType.GITHUB_COMMIT.name &&
                JSONObject(it.configJson).optJSONObject("recurrence") == null
        }
        toReshape.forEach { goalDao.upsert(it.reshapeGitHubToRecurring()) }
        settings.setGithubRecurringMigrated(true)
        if (toReshape.isNotEmpty()) {
            Timber.tag("GitHub").i("Reshaped %d GitHub goal(s) to the recurring model", toReshape.size)
        }
    }

    companion object {
        const val DAILY_REWARD_POINTS = 30   // ~3 pages' worth for a commit period
        const val DAILY_PENALTY_SHORTS = 5   // docked from the allowance on a missed period
    }
}

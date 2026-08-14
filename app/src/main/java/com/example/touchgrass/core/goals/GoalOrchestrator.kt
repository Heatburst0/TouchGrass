package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.GoalDao
import com.example.touchgrass.core.data.db.GoalEntity
import com.example.touchgrass.core.rewards.RewardsManager
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The unified goal spine. Every goal that lives in the `goals` table earns and
 * loses through here, via ONE mapping from [VerificationResult] to progress +
 * economy — so a new goal type never re-implements reward/penalty plumbing.
 *
 * Two ways a result reaches the spine:
 *  - PULL: [runPolled] iterates active goals whose verifier is [VerificationCadence.Polled]
 *    (WorkManager cadence), calls verify(), and applies the result.
 *  - PUSH: a feature calls [report] / [reportProgress] when something happens live
 *    (a quiz passes, the accessibility service sees a short) and the spine applies it.
 *
 * Verifiers routed through the spine must be PURE — return a result, don't touch
 * the economy themselves (the spine owns that). The legacy GitHub verifier still
 * settles its own multi-day economy internally and is NOT routed here yet; it
 * migrates to a pure verifier in a later step.
 */
@Singleton
class GoalOrchestrator @Inject constructor(
    private val verifiers: Map<GoalType, @JvmSuppressWildcards GoalVerifier>,
    private val goalDao: GoalDao,
    private val rewards: RewardsManager
) {

    /** PULL: run every active goal whose verifier polls. */
    suspend fun runPolled() {
        goalDao.observeActive().first().forEach { goal ->
            val verifier = verifierFor(goal) ?: return@forEach
            if (verifier.cadence !is VerificationCadence.Polled) return@forEach
            try {
                applyResult(goal, verifier.verify(goal))
            } catch (e: Exception) {
                // Network/API/transient failure — leave state untouched, retry next run.
                Timber.tag("Goals").w(e, "Polled verify failed for goal %d (%s)", goal.id, goal.type)
            }
        }
    }

    /** PUSH: a live event reports a result for a specific goal. */
    suspend fun report(goalId: Long, result: VerificationResult) {
        val goal = goalDao.byId(goalId) ?: return
        applyResult(goal, result)
    }

    /** PUSH convenience: add [units] of verified progress to every active goal of
     *  [type] (e.g. verified reading pages → every active reading goal). */
    suspend fun reportProgress(type: GoalType, units: Int) {
        if (units <= 0) return
        goalDao.activeOfType(type.name).forEach { goal ->
            applyResult(goal, VerificationResult.Progress(units))
        }
    }

    /**
     * The single result → (progress, economy) mapping. ACHIEVE goals earn their
     * reward when they reach target; any goal that Fails a cycle is docked its stake.
     */
    private suspend fun applyResult(goal: GoalEntity, result: VerificationResult) {
        when (result) {
            is VerificationResult.Progress -> {
                val newProgress = goal.progress + result.amount
                if (newProgress >= goal.target) {
                    goalDao.upsert(goal.copy(progress = goal.target))
                    reward(goal, "goal_met:${goal.id}")
                } else {
                    goalDao.upsert(goal.copy(progress = newProgress))
                }
            }
            VerificationResult.Passed -> {
                goalDao.upsert(goal.copy(progress = maxOf(goal.progress, goal.target)))
                reward(goal, "goal_passed:${goal.id}")
            }
            VerificationResult.Failed -> {
                if (goal.penaltyShorts > 0) rewards.applyPenaltyShorts(goal.penaltyShorts)
            }
            VerificationResult.NoChange -> Unit
        }
    }

    private suspend fun reward(goal: GoalEntity, reason: String) {
        if (goal.rewardPoints > 0) rewards.award(goal.rewardPoints, reason)
    }

    private fun verifierFor(goal: GoalEntity): GoalVerifier? =
        runCatching { GoalType.valueOf(goal.type) }.getOrNull()?.let { verifiers[it] }
}

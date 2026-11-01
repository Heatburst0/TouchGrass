package com.example.touchgrass.core.goals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
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

    /**
     * Polled verify+apply runs one goal at a time on a fresh read: a background poll
     * and a final check racing on the same goal would otherwise both credit the same
     * minutes (and pay the reward twice). Push paths don't take it — they can run
     * inside a Room transaction, where waiting on this lock could deadlock.
     */
    private val pollLock = Mutex()

    /** PULL: run every active goal whose verifier polls. */
    suspend fun runPolled() {
        goalDao.observeActive().first().forEach { listed ->
            val verifier = verifierFor(listed) ?: return@forEach
            if (verifier.cadence !is VerificationCadence.Polled) return@forEach
            pollLock.withLock {
                val goal = goalDao.byId(listed.id)?.takeIf { it.active } ?: return@withLock
                try {
                    applyResult(goal, verifier.verify(goal))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Network/API/transient failure — leave state untouched, retry next run.
                    Timber.tag("Goals").w(e, "Polled verify failed for goal %d (%s)", goal.id, goal.type)
                }
            }
        }
    }

    /**
     * Last look before a period closes: polled [goals] whose period has ended get one
     * more verify, credited to that period, so work done just before the boundary
     * (but after the last poll) still counts instead of being settled as missed.
     * Returns the ids that couldn't be checked (offline/not signed in yet).
     */
    suspend fun finalCheck(goals: List<GoalEntity>): Set<Long> {
        val unchecked = mutableSetOf<Long>()
        goals.forEach { listed ->
            val verifier = verifierFor(listed) ?: return@forEach
            if (verifier.cadence !is VerificationCadence.Polled) return@forEach
            pollLock.withLock {
                val goal = goalDao.byId(listed.id) ?: return@withLock
                try {
                    applyResult(goal, verifier.verify(goal), occurredAt = goal.periodEndAt() - 1)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag("Goals").w(e, "Final check failed for goal %d (%s)", goal.id, goal.type)
                    unchecked += goal.id
                }
            }
        }
        return unchecked
    }

    /** PUSH: a live event reports a result for a specific goal. */
    suspend fun report(goalId: Long, result: VerificationResult) {
        val goal = goalDao.byId(goalId) ?: return
        applyResult(goal, result)
    }

    /** PUSH convenience: add [units] of verified progress to every active goal of
     *  [type] (e.g. verified reading pages → every active reading goal). An event
     *  that [occurredAt] before a goal existed (e.g. history pulled by sync) never
     *  counts toward it. */
    suspend fun reportProgress(type: GoalType, units: Int, occurredAt: Long = System.currentTimeMillis()) {
        if (units <= 0) return
        goalDao.activeOfType(type.name)
            .filter { it.existedAt(occurredAt) }
            .forEach { goal -> applyResult(goal, VerificationResult.Progress(units), occurredAt) }
    }

    /**
     * The single result → (progress, economy) mapping. ACHIEVE goals earn their
     * reward once when they reach target (one-shots then close as MET); any goal
     * that Fails a cycle is docked its stake.
     */
    private suspend fun applyResult(
        goal: GoalEntity,
        result: VerificationResult,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        val now = System.currentTimeMillis()
        val update = when (result) {
            is VerificationResult.Progress -> goal.applyProgress(result.amount, now, occurredAt)
            VerificationResult.Passed -> goal.applyPassed(now, occurredAt)
            VerificationResult.Failed -> {
                if (goal.penaltyShorts > 0) rewards.applyPenaltyShorts(goal.penaltyShorts)
                GoalUpdate.NONE
            }
            VerificationResult.NoChange -> GoalUpdate.NONE
        }
        update.goal?.let { goalDao.upsert(it) }
        update.rewardReason?.let { reward(goal, it) }
    }

    private suspend fun reward(goal: GoalEntity, reason: String) {
        if (goal.rewardPoints > 0) rewards.award(goal.rewardPoints, reason)
    }

    private fun verifierFor(goal: GoalEntity): GoalVerifier? =
        runCatching { GoalType.valueOf(goal.type) }.getOrNull()?.let { verifiers[it] }
}

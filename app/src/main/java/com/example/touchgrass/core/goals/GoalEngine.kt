package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.CommitmentDao
import com.example.touchgrass.core.data.db.CommitmentEntity
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reward/punishment spine. Verified actions from any pillar call
 * [recordProgress]; the engine advances matching pledges, pays the bonus when
 * one is met, and — on [settleOverdue] — docks entertainment time for pledges
 * whose deadline passed unmet.
 *
 * Reward and punishment both flow through [RewardsManager], so the economy has
 * exactly one door regardless of which pillar produced the event.
 */
@Singleton
class GoalEngine @Inject constructor(
    private val dao: CommitmentDao,
    private val rewards: RewardsManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    val activeCommitments: StateFlow<List<CommitmentEntity>> = dao.observeActive()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pastCommitments: StateFlow<List<CommitmentEntity>> = dao.observePast()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Settle anything that expired while the app was closed.
        scope.launch { settleOverdue() }
    }

    fun createCommitment(
        pillar: PillarType,
        title: String,
        targetAmount: Int,
        deadlineAt: Long,
        rewardPoints: Int,
        penaltyShorts: Int
    ) {
        scope.launch {
            dao.insert(
                CommitmentEntity(
                    pillar = pillar.name,
                    title = title.ifBlank { "${pillar.display} goal" },
                    targetAmount = targetAmount.coerceAtLeast(1),
                    unitLabel = pillar.unit,
                    progress = 0,
                    createdAt = System.currentTimeMillis(),
                    deadlineAt = deadlineAt,
                    rewardPoints = rewardPoints.coerceAtLeast(0),
                    penaltyShorts = penaltyShorts.coerceAtLeast(0),
                    status = CommitmentStatus.ACTIVE.name
                )
            )
            Timber.tag("Goals").i("New %s pledge: %d %s", pillar.display, targetAmount, pillar.unit)
        }
    }

    /**
     * Report [units] of verified work for [pillar]. Advances every active,
     * not-yet-expired pledge for that pillar; any that reach target are marked
     * MET and paid their bonus.
     */
    fun recordProgress(pillar: PillarType, units: Int) {
        if (units <= 0) return
        scope.launch {
            settleOverdue()
            val now = System.currentTimeMillis()
            dao.activeForPillar(pillar.name, now).forEach { c ->
                val newProgress = c.progress + units
                if (newProgress >= c.targetAmount) {
                    dao.update(c.copy(progress = c.targetAmount, status = CommitmentStatus.MET.name))
                    rewards.award(c.rewardPoints, "commitment_met:${c.id}")
                    Timber.tag("Goals").i("Pledge #%d MET (+%d pts)", c.id, c.rewardPoints)
                } else {
                    dao.update(c.copy(progress = newProgress))
                }
            }
        }
    }

    /** Marks expired active pledges MISSED and applies their screen-time penalty. */
    suspend fun settleOverdue() {
        val now = System.currentTimeMillis()
        dao.overdue(now).forEach { c ->
            dao.update(c.copy(status = CommitmentStatus.MISSED.name))
            rewards.applyPenaltyShorts(c.penaltyShorts)
            Timber.tag("Goals").i("Pledge #%d MISSED (-%d shorts)", c.id, c.penaltyShorts)
        }
    }
}

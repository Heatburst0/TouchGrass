package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.CommitmentDao
import com.example.touchgrass.core.data.db.CommitmentEntity
import com.example.touchgrass.core.data.db.GoalDao
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reward/punishment spine for pledges. As of the Goal+Verifier refactor
 * (Step A) pledges are ONE_SHOT Task goals in the unified `goals` table, but this
 * class keeps its exact public surface (create / recordProgress / settleOverdue /
 * active + past commitments as [CommitmentEntity]) so the Goals UI, dashboard, and
 * reading credit path were untouched.
 *
 * Reward and punishment still flow through [RewardsManager] — one economy door.
 */
@Singleton
class GoalEngine @Inject constructor(
    private val goalDao: GoalDao,
    private val legacyDao: CommitmentDao,      // one-time migration of pre-refactor rows
    private val rewards: RewardsManager,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    val activeCommitments: StateFlow<List<CommitmentEntity>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type == GoalType.TASK.name && it.active }
                .sortedBy { it.deadlineAt ?: Long.MAX_VALUE }
                .map { it.toCommitment() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pastCommitments: StateFlow<List<CommitmentEntity>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type == GoalType.TASK.name && !it.active }
                .sortedByDescending { it.deadlineAt ?: 0L }
                .take(30)
                .map { it.toCommitment() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            migrateLegacyPledges()
            // Settle anything that expired while the app was closed.
            settleOverdue()
        }
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
            goalDao.upsert(
                newPledgeGoal(
                    pillar = pillar,
                    title = title.ifBlank { "${pillar.display} goal" },
                    target = targetAmount.coerceAtLeast(1),
                    deadlineAt = deadlineAt,
                    reward = rewardPoints.coerceAtLeast(0),
                    penalty = penaltyShorts.coerceAtLeast(0),
                    now = System.currentTimeMillis()
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
            goalDao.activeOfType(GoalType.TASK.name)
                .filter { it.pledgeCategory() == pillar.name && (it.deadlineAt ?: Long.MAX_VALUE) >= now }
                .forEach { g ->
                    val newProgress = g.progress + units
                    if (newProgress >= g.target) {
                        goalDao.upsert(g.copy(progress = g.target).withStatus(CommitmentStatus.MET))
                        rewards.award(g.rewardPoints, "commitment_met:${g.id}")
                        Timber.tag("Goals").i("Pledge #%d MET (+%d pts)", g.id, g.rewardPoints)
                    } else {
                        goalDao.upsert(g.copy(progress = newProgress))
                    }
                }
        }
    }

    /** Marks expired active pledges MISSED and applies their screen-time penalty. */
    suspend fun settleOverdue() {
        val now = System.currentTimeMillis()
        goalDao.activeOfType(GoalType.TASK.name)
            .filter { (it.deadlineAt ?: Long.MAX_VALUE) < now }
            .forEach { g ->
                goalDao.upsert(g.withStatus(CommitmentStatus.MISSED))
                rewards.applyPenaltyShorts(g.penaltyShorts)
                Timber.tag("Goals").i("Pledge #%d MISSED (-%d shorts)", g.id, g.penaltyShorts)
            }
    }

    private suspend fun migrateLegacyPledges() {
        if (settings.pledgesMigrated.first()) return
        val now = System.currentTimeMillis()
        val legacy = legacyDao.observeActive().first() + legacyDao.observePast().first()
        legacy.forEach { goalDao.upsert(it.toPledgeGoal(now)) }
        settings.setPledgesMigrated(true)
        if (legacy.isNotEmpty()) {
            Timber.tag("Goals").i("Migrated %d pledge(s) into the goals table", legacy.size)
        }
    }
}

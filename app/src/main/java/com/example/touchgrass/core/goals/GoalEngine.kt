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

    /** Richer read models for the Goals screen (recurrence + streak aware). */
    val activeGoals: StateFlow<List<GoalView>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type == GoalType.TASK.name && it.active }
                .sortedBy { it.deadlineAt ?: Long.MAX_VALUE }
                .map { it.toGoalView() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pastGoals: StateFlow<List<GoalView>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type == GoalType.TASK.name && !it.active }
                .sortedByDescending { it.deadlineAt ?: 0L }
                .take(30)
                .map { it.toGoalView() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init { scope.launch { migrateLegacyPledges(); settleOverdue(); settleRecurring() } }


    fun createCommitment(
        pillar: PillarType, title: String, targetAmount: Int, deadlineAt: Long,
        rewardPoints: Int, penaltyShorts: Int,
        recurrence: Recurrence = Recurrence.Once           // NEW
    ) {
        scope.launch {
            goalDao.upsert(
                newPledgeGoal(pillar, title.ifBlank { "${pillar.display} goal" },
                    targetAmount.coerceAtLeast(1), deadlineAt,
                    rewardPoints.coerceAtLeast(0), penaltyShorts.coerceAtLeast(0),
                    System.currentTimeMillis(), recurrence)               // NEW arg
            )
            Timber.tag("Goals").i("New %s pledge (%s)", pillar.display, recurrence)
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
            settleOverdue(); settleRecurring()
            val now = System.currentTimeMillis()
            goalDao.activeOfType(GoalType.TASK.name)
                .filter { it.pledgeCategory() == pillar.name }
                .forEach { g ->
                    if (g.recurrence() != Recurrence.Once) {
                        if (g.metThisPeriod()) return@forEach
                        val np = g.progress + units
                        if (np >= g.target) {
                            goalDao.upsert(g.copy(progress = g.target).withRecurringState(met = true))
                            rewards.award(g.rewardPoints, "goal_met:${g.id}:period")
                        } else goalDao.upsert(g.copy(progress = np))
                    } else {
                        if ((g.deadlineAt ?: Long.MAX_VALUE) < now) return@forEach
                        val np = g.progress + units
                        if (np >= g.target) {
                            goalDao.upsert(g.copy(progress = g.target).withStatus(CommitmentStatus.MET))
                            rewards.award(g.rewardPoints, "commitment_met:${g.id}")
                        } else goalDao.upsert(g.copy(progress = np))
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

    suspend fun settleRecurring() {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        goalDao.activeOfType(GoalType.TASK.name)
            .filter { it.recurrence() != Recurrence.Once }
            .forEach { g0 ->
                var g = g0
                var guard = 0
                while (g.periodEndAt() in 1..now && guard < 370) {
                    g = if (g.metThisPeriod()) {
                        val s = g.currentStreak() + 1
                        g.withRecurringState(streak = s, best = maxOf(g.bestStreak(), s))
                    } else {
                        rewards.applyPenaltyShorts(g.penaltyShorts)
                        g.withRecurringState(streak = 0)
                    }
                    val endDate = java.time.Instant.ofEpochMilli(g.periodEndAt())
                        .atZone(zone).toLocalDate()
                    val next = RecurrenceSchedule.periodAtOrAfter(g.recurrence(), endDate, zone) ?: break
                    g = g.copy(progress = 0).withRecurringState(periodEndAt = next.endAt, met = false)
                    goalDao.upsert(g)
                    guard++
                }
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

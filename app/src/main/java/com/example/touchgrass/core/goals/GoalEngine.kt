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
    /** Goal types the Goals screen renders as "pledges": manual TASK goals and
     *  first-class READING goals (auto-credited by the reading path). GitHub,
     *  focus, and shorts are their own surfaces. */
    private val pledgeTypes = setOf(GoalType.TASK.name, GoalType.READING.name)

    val activeCommitments: StateFlow<List<CommitmentEntity>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type in pledgeTypes && it.active }
                .sortedBy { it.deadlineAt ?: Long.MAX_VALUE }
                .map { it.toCommitment() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pastCommitments: StateFlow<List<CommitmentEntity>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type in pledgeTypes && !it.active }
                .sortedByDescending { it.deadlineAt ?: 0L }
                .take(30)
                .map { it.toCommitment() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Richer read models for the Goals screen (recurrence + streak aware). */
    val activeGoals: StateFlow<List<GoalView>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type in pledgeTypes && it.active }
                .sortedBy { it.deadlineAt ?: Long.MAX_VALUE }
                .map { it.toGoalView() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pastGoals: StateFlow<List<GoalView>> =
        goalDao.observeAll().map { all ->
            all.filter { it.type in pledgeTypes && !it.active }
                .sortedByDescending { it.deadlineAt ?: 0L }
                .take(30)
                .map { it.toGoalView() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init { scope.launch { migrateLegacyPledges(); settleOverdue(); settleRecurring() } }


    fun createCommitment(
        goalType: GoalType, category: String, unit: String,
        title: String, targetAmount: Int, deadlineAt: Long,
        rewardPoints: Int, penaltyShorts: Int,
        recurrence: Recurrence = Recurrence.Once
    ) {
        scope.launch {
            goalDao.upsert(
                newPledgeGoal(goalType, category, unit, title.ifBlank { "$category goal" },
                    targetAmount.coerceAtLeast(1), deadlineAt,
                    rewardPoints.coerceAtLeast(0), penaltyShorts.coerceAtLeast(0),
                    System.currentTimeMillis(), recurrence)
            )
            Timber.tag("Goals").i("New %s pledge (%s)", category, recurrence)
        }
    }


    /**
     * Report [units] of verified work for goal [type] (e.g. verified reading pages
     * → every active READING goal). Advances every active, not-yet-expired pledge
     * of that type; any that reach target are marked MET and paid their bonus.
     */
    fun recordProgress(type: GoalType, units: Int) {
        if (units <= 0) return
        scope.launch {
            settleOverdue(); settleRecurring()
            val now = System.currentTimeMillis()
            goalDao.activeOfType(type.name)
                .forEach { g ->
                    if (g.recurrence() != Recurrence.Once) {
                        val (updated, metNow) = g.addRecurringProgress(units)
                        goalDao.upsert(updated)
                        if (metNow) rewards.award(g.rewardPoints, "goal_met:${g.id}:period")
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
        goalDao.observeActive().first()
            .filter { it.type in pledgeTypes && it.recurrence() == Recurrence.Once }
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
        // Every recurring goal, of any type (reading Tasks AND GitHub commits).
        goalDao.observeActive().first()
            .filter { it.recurrence() != Recurrence.Once }
            .forEach { g0 ->
                var g = g0
                // A met current period is always at least a 1-streak (streak is now
                // counted when a period is MET). Heals goals left at 0 by the old order.
                if (g.metThisPeriod() && g.currentStreak() < 1) {
                    g = g.withRecurringState(streak = 1, best = maxOf(g.bestStreak(), 1))
                    goalDao.upsert(g)
                }
                var guard = 0
                while (g.periodEndAt() in 1..now && guard < 370) {
                    if (!g.metThisPeriod()) {
                        rewards.applyPenaltyShorts(g.penaltyShorts)
                        g = g.withRecurringState(streak = 0)
                    }
                    // A met period keeps its streak (already counted at meet-time); just roll.
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

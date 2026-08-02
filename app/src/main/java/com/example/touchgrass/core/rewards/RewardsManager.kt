package com.example.touchgrass.core.rewards

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.PointsDao
import com.example.touchgrass.core.data.db.PointsEntryEntity
import com.example.touchgrass.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The points economy shared by all productivity tools.
 *
 * Earning: any tool can call [award] with a reason (reading pages today,
 * focus sessions / step goals later). Spending: currently only [redeemExtraShorts].
 * Every movement is a ledger row, so history and gamification come for free.
 */
@Singleton
class RewardsManager @Inject constructor(
    private val pointsDao: PointsDao,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    val pointsBalance: StateFlow<Int> = pointsDao.balance()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Extra shorts unlocked today on top of the base daily limit. */
    val extraShortsToday: StateFlow<Int> = settings.extraShortsToday
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Shorts docked today for missed commitments (subtracted from the limit). */
    val penaltyShortsToday: StateFlow<Int> = settings.penaltyShortsToday
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Punishment currency: shrink today's entertainment allowance. */
    suspend fun applyPenaltyShorts(amount: Int) {
        if (amount <= 0) return
        settings.addPenaltyShorts(amount)
        Timber.tag("Rewards").i("Penalty: -%d shorts today", amount)
    }

    suspend fun award(points: Int, reason: String) {
        pointsDao.insert(
            PointsEntryEntity(delta = points, reason = reason, createdAt = System.currentTimeMillis())
        )
        Timber.tag("Rewards").d("+%d pts (%s)", points, reason)
    }

    suspend fun awardPageRead(bookId: Long, pageIndex: Int) {
        award(POINTS_PER_PAGE, "page_read:$bookId:$pageIndex")
        // A quiz-verified page satisfies the force-read gate, if one is owed
        settings.setReadingGatePending(false)
    }

    /**
     * Spends [SKIP_GATE_COST] points to dismiss the current force-read gate, so
     * the user can keep watching without reading this time. Re-arms on the next
     * watch-time interval. Returns false if the balance is too low.
     */
    suspend fun redeemSkipReadingGate(): Boolean {
        if (pointsBalance.value < SKIP_GATE_COST) return false
        pointsDao.insert(
            PointsEntryEntity(
                delta = -SKIP_GATE_COST,
                reason = "skip_reading_gate",
                createdAt = System.currentTimeMillis()
            )
        )
        settings.setReadingGatePending(false)
        Timber.tag("Rewards").i("Spent %d pts to skip reading gate", SKIP_GATE_COST)
        return true
    }

    /** Trades [SHORTS_UNLOCK_COST] points for [SHORTS_UNLOCK_AMOUNT] extra shorts today. */
    suspend fun redeemExtraShorts(): Boolean {
        if (pointsBalance.value < SHORTS_UNLOCK_COST) return false
        pointsDao.insert(
            PointsEntryEntity(
                delta = -SHORTS_UNLOCK_COST,
                reason = "redeem_extra_shorts",
                createdAt = System.currentTimeMillis()
            )
        )
        settings.addExtraShorts(SHORTS_UNLOCK_AMOUNT)
        Timber.tag("Rewards").i("Redeemed %d pts for +%d shorts", SHORTS_UNLOCK_COST, SHORTS_UNLOCK_AMOUNT)
        return true
    }

    companion object {
        const val POINTS_PER_PAGE = 10
        const val SHORTS_UNLOCK_COST = 20   // = 2 verified pages
        const val SHORTS_UNLOCK_AMOUNT = 5
        const val SKIP_GATE_COST = 50       // = 5 pages' worth to skip a forced read
    }
}

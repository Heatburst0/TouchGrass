package com.example.touchgrass.features.reading

import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.core.goals.PillarType
import com.example.touchgrass.core.rewards.RewardsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single door for crediting verified reading pages, no matter where they come
 * from — the PDF reader, the force-read gate, or a physical-book photo session.
 *
 * It owns only the SHARED part: per-page points, clearing the force-read gate, and
 * advancing active reading goals. The caller marks the pages verified first, since
 * that legitimately differs by source (PDF: verifyPages / physical: creditPhysicalPages).
 *
 * This is why "read a page through force-read mode" already counts toward a reading
 * pledge — there is exactly one place that decides what a verified page is worth.
 *
 * (Increment 2: the goalEngine.recordProgress call becomes
 * GoalOrchestrator.reportProgress(READING, ...) once reading goals live in the
 * goals table.)
 */
@Singleton
class ReadingCredit @Inject constructor(
    private val rewards: RewardsManager,
    private val goalEngine: GoalEngine
) {
    suspend fun recordVerifiedPages(bookId: Long, pages: List<Int>) {
        if (pages.isEmpty()) return
        pages.forEach { rewards.awardPageRead(bookId, it) }        // per-page points + clears force-read gate
        goalEngine.recordProgress(PillarType.READING, pages.size) // advance active reading goals
    }
}

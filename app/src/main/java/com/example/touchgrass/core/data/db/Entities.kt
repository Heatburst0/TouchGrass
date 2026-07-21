package com.example.touchgrass.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,          // empty for physical books
    val pageCount: Int,            // 0 for physical books (unknown)
    val addedAt: Long,
    val lastPage: Int = 0,
    val type: String = TYPE_PDF
) {
    companion object {
        const val TYPE_PDF = "PDF"
        const val TYPE_PHYSICAL = "PHYSICAL"
    }
}

/**
 * One row per page the user has read. `verified = false` means the page was
 * dwell-read but hasn't passed an AI quiz yet — no points until it does.
 */
@Entity(tableName = "page_reads", primaryKeys = ["bookId", "pageIndex"])
data class PageReadEntity(
    val bookId: Long,
    val pageIndex: Int,
    val readAt: Long,
    val verified: Boolean = true
)

/**
 * Append-only points ledger. Balance = SUM(delta). Every productivity tool
 * (reading today, focus sessions tomorrow) earns/spends through this table.
 */
@Entity(tableName = "points_ledger")
data class PointsEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val delta: Int,
    val reason: String,
    val createdAt: Long
)

/**
 * A verified pledge: do [targetAmount] [unitLabel] of [pillar] by [deadlineAt].
 * Meet it → [rewardPoints] bonus; miss it → [penaltyShorts] docked from today's
 * entertainment allowance. `pillar` and `status` are stored as enum names.
 */
@Entity(tableName = "commitments")
data class CommitmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pillar: String,
    val title: String,
    val targetAmount: Int,
    val unitLabel: String,
    val progress: Int,
    val createdAt: Long,
    val deadlineAt: Long,
    val rewardPoints: Int,
    val penaltyShorts: Int,
    val status: String
)

/**
 * A recurring "commit daily to this repo" goal, verified against the GitHub
 * API in the background. Dates are local `yyyy-MM-dd` strings. `author` blank
 * = any commit to the repo counts (good for a solo repo).
 */
@Entity(tableName = "github_goals")
data class GitHubGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val owner: String,
    val repo: String,
    val author: String,
    val createdDate: String,
    val lastSuccessDate: String?,   // last day a commit was detected
    val lastSettledDate: String?,   // last elapsed day we finalized (reward/penalty)
    val currentStreak: Int,
    val bestStreak: Int,
    val rewardPoints: Int,
    val penaltyShorts: Int,
    val active: Boolean
)

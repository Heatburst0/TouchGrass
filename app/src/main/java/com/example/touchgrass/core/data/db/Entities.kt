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

/** One row per page the user has verifiably read (dwell-verified). */
@Entity(tableName = "page_reads", primaryKeys = ["bookId", "pageIndex"])
data class PageReadEntity(
    val bookId: Long,
    val pageIndex: Int,
    val readAt: Long
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

package com.example.touchgrass.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class BookWithProgress(
    @Embedded val book: BookEntity,
    val pagesRead: Int
)

@Dao
interface BookDao {
    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query(
        """
        SELECT b.*,
               (SELECT COUNT(*) FROM page_reads p
                WHERE p.bookId = b.id AND p.verified = 1) AS pagesRead
        FROM books b ORDER BY b.addedAt DESC
        """
    )
    fun booksWithProgress(): Flow<List<BookWithProgress>>

    @Query("UPDATE books SET lastPage = :page WHERE id = :id")
    suspend fun updateLastPage(id: Long, page: Int)

    @Query("SELECT * FROM books WHERE type = 'PDF' ORDER BY addedAt DESC LIMIT 1")
    suspend fun latestPdfBook(): BookEntity?

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PageReadDao {
    /** Returns -1 when the page was already credited (conflict ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(read: PageReadEntity): Long

    @Query("SELECT pageIndex FROM page_reads WHERE bookId = :bookId AND verified = 1")
    fun verifiedPages(bookId: Long): Flow<List<Int>>

    @Query("SELECT pageIndex FROM page_reads WHERE bookId = :bookId AND verified = 0")
    fun pendingPages(bookId: Long): Flow<List<Int>>

    @Query("UPDATE page_reads SET verified = 1 WHERE bookId = :bookId AND pageIndex IN (:pages)")
    suspend fun markVerified(bookId: Long, pages: List<Int>)

    @Query("SELECT COALESCE(MAX(pageIndex), -1) FROM page_reads WHERE bookId = :bookId")
    suspend fun maxPageIndex(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM page_reads WHERE readAt >= :since")
    fun pagesReadSince(since: Long): Flow<Int>
}

/** Aggregate stats for the Focus screen header. */
data class FocusStats(
    val completed: Int,
    val total: Int,
    val focusedMinutes: Int
)

@Dao
interface FocusSessionDao {
    @Insert
    suspend fun insert(session: FocusSessionEntity): Long

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<FocusSessionEntity>>

    /** Idempotency guard: a session's start-time uniquely identifies it, so a
     *  double settle (alarm + app-open + End) records it only once. */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE startedAt = :startedAt")
    suspend fun countByStart(startedAt: Long): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN outcome = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed,
            COUNT(*) AS total,
            COALESCE(SUM(focusedMin), 0) AS focusedMinutes
        FROM focus_sessions
        """
    )
    fun observeStats(): Flow<FocusStats>
}

@Dao
interface PointsDao {
    @Insert
    suspend fun insert(entry: PointsEntryEntity)

    @Query("SELECT COALESCE(SUM(delta), 0) FROM points_ledger")
    fun balance(): Flow<Int>

    @Query("SELECT COALESCE(SUM(delta), 0) FROM points_ledger WHERE delta > 0")
    fun lifetimeEarned(): Flow<Int>
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity): Long

    @Update suspend fun update(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE active = 1 AND type = :type")
    suspend fun activeOfType(type: String): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun byId(id: Long): GoalEntity?

    @Query("SELECT * FROM goals")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: Long)
}


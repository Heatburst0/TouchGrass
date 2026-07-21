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

@Dao
interface CommitmentDao {
    @Insert
    suspend fun insert(commitment: CommitmentEntity): Long

    @Update
    suspend fun update(commitment: CommitmentEntity)

    @Query("SELECT * FROM commitments WHERE status = 'ACTIVE' ORDER BY deadlineAt ASC")
    fun observeActive(): Flow<List<CommitmentEntity>>

    @Query("SELECT * FROM commitments WHERE status IN ('MET','MISSED') ORDER BY deadlineAt DESC LIMIT 30")
    fun observePast(): Flow<List<CommitmentEntity>>

    @Query("SELECT * FROM commitments WHERE pillar = :pillar AND status = 'ACTIVE' AND deadlineAt >= :now")
    suspend fun activeForPillar(pillar: String, now: Long): List<CommitmentEntity>

    @Query("SELECT * FROM commitments WHERE status = 'ACTIVE' AND deadlineAt < :now")
    suspend fun overdue(now: Long): List<CommitmentEntity>
}

@Dao
interface GitHubGoalDao {
    @Insert
    suspend fun insert(goal: GitHubGoalEntity): Long

    @Update
    suspend fun update(goal: GitHubGoalEntity)

    @Query("DELETE FROM github_goals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM github_goals ORDER BY id DESC")
    fun observeAll(): Flow<List<GitHubGoalEntity>>

    @Query("SELECT * FROM github_goals WHERE active = 1")
    suspend fun activeGoals(): List<GitHubGoalEntity>
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

package com.example.touchgrass.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
               (SELECT COUNT(*) FROM page_reads p WHERE p.bookId = b.id) AS pagesRead
        FROM books b ORDER BY b.addedAt DESC
        """
    )
    fun booksWithProgress(): Flow<List<BookWithProgress>>

    @Query("UPDATE books SET lastPage = :page WHERE id = :id")
    suspend fun updateLastPage(id: Long, page: Int)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PageReadDao {
    /** Returns -1 when the page was already credited (conflict ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(read: PageReadEntity): Long

    @Query("SELECT pageIndex FROM page_reads WHERE bookId = :bookId")
    fun readPages(bookId: Long): Flow<List<Int>>

    @Query("SELECT COALESCE(MAX(pageIndex), -1) FROM page_reads WHERE bookId = :bookId")
    suspend fun maxPageIndex(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM page_reads WHERE readAt >= :since")
    fun pagesReadSince(since: Long): Flow<Int>
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

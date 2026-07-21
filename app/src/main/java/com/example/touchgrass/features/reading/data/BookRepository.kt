package com.example.touchgrass.features.reading.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.touchgrass.core.data.db.BookDao
import com.example.touchgrass.core.data.db.BookEntity
import com.example.touchgrass.core.data.db.BookWithProgress
import com.example.touchgrass.core.data.db.PageReadDao
import com.example.touchgrass.core.data.db.PageReadEntity
import com.example.touchgrass.features.reading.pdf.PdfBookRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val pageReadDao: PageReadDao
) {
    val books: Flow<List<BookWithProgress>> = bookDao.booksWithProgress()

    suspend fun getBook(id: Long): BookEntity? = bookDao.get(id)

    /** Quiz-verified pages — the only ones worth points. */
    fun verifiedPages(bookId: Long): Flow<List<Int>> = pageReadDao.verifiedPages(bookId)

    /** Dwell-read pages awaiting quiz verification. */
    fun pendingPages(bookId: Long): Flow<List<Int>> = pageReadDao.pendingPages(bookId)

    /**
     * Imports a picked PDF: copies it into private storage (no lingering URI
     * permission issues), counts pages, and registers it in the library.
     */
    suspend fun addBook(uri: Uri): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open the selected PDF")

        val pageCount = try {
            PdfBookRenderer.pageCount(file)
        } catch (e: Exception) {
            file.delete()
            throw IllegalStateException("Not a readable PDF", e)
        }

        val title = displayName(uri)?.removeSuffix(".pdf") ?: "Untitled book"
        val id = bookDao.insert(
            BookEntity(
                title = title,
                filePath = file.absolutePath,
                pageCount = pageCount,
                addedAt = System.currentTimeMillis()
            )
        )
        Timber.tag("Reading").i("Imported \"%s\" (%d pages)", title, pageCount)
        id
    }

    /** Registers a paper book — pages get verified via photo + AI quiz. */
    suspend fun addPhysicalBook(title: String): Long =
        bookDao.insert(
            BookEntity(
                title = title.trim().ifBlank { "Untitled book" },
                filePath = "",
                pageCount = 0,
                addedAt = System.currentTimeMillis(),
                type = BookEntity.TYPE_PHYSICAL
            )
        )

    /**
     * Credits [count] quiz-verified pages of a physical book, continuing from
     * the highest index already credited. Returns the credited page indexes.
     */
    suspend fun creditPhysicalPages(bookId: Long, count: Int): List<Int> {
        val start = pageReadDao.maxPageIndex(bookId) + 1
        val now = System.currentTimeMillis()
        return (start until start + count).filter { index ->
            pageReadDao.insertIgnore(
                PageReadEntity(bookId = bookId, pageIndex = index, readAt = now)
            ) != -1L
        }
    }

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        if (book.filePath.isNotBlank()) File(book.filePath).delete()
        bookDao.delete(book.id)
    }

    suspend fun saveLastPage(bookId: Long, page: Int) = bookDao.updateLastPage(bookId, page)

    /**
     * Records a dwell-read page as PENDING (unverified — no points yet).
     * Returns false if the page already has a row (pending or verified).
     */
    suspend fun markPagePending(bookId: Long, pageIndex: Int): Boolean =
        pageReadDao.insertIgnore(
            PageReadEntity(
                bookId = bookId,
                pageIndex = pageIndex,
                readAt = System.currentTimeMillis(),
                verified = false
            )
        ) != -1L

    /** Flips quiz-passed pages to verified; caller awards the points. */
    suspend fun verifyPages(bookId: Long, pages: List<Int>) =
        pageReadDao.markVerified(bookId, pages)

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}

package com.example.touchgrass.features.reading.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thread-safe wrapper around [PdfRenderer] (which itself is not thread-safe).
 * One instance per open book; close it when the reader screen goes away.
 */
class PdfBookRenderer(private val file: File) {

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val mutex = Mutex()

    private fun ensureOpen(): PdfRenderer {
        renderer?.let { return it }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = descriptor
        return PdfRenderer(descriptor).also { renderer = it }
    }

    suspend fun render(pageIndex: Int, targetWidth: Int): Bitmap =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val r = ensureOpen()
                r.openPage(pageIndex).use { page ->
                    val scale = targetWidth.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    suspend fun close() = mutex.withLock {
        renderer?.close()
        renderer = null
        pfd?.close()
        pfd = null
    }

    companion object {
        /** Opens the file just long enough to count its pages. */
        fun pageCount(file: File): Int =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
    }
}

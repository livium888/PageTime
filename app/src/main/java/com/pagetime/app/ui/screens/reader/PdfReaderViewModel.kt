package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a PDF page-by-page using Android's built-in PdfRenderer.
 *
 * Unlike the EPUB conversion path, this keeps the document exactly as it was
 * printed — layout, figures, tables, and all. The trade-off is no text
 * selection or locator-based highlights, but the reading experience is what
 * the author intended, which is what the reader asked for.
 */
class PdfReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PdfState())
    val state = _state.asStateFlow()

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var bookId: String? = null

    /** Open a PDF file and prepare for reading. Restores the last position. */
    fun open(pdfPath: String, bookId: String) {
        this.bookId = bookId
        viewModelScope.launch {
            val file = File(pdfPath)
            if (!file.exists()) {
                _state.value = PdfState(error = "PDF file not found")
                return@launch
            }
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd!!)
                val count = renderer!!.pageCount
                // Restore last position
                val savedPage = restorePosition(bookId)
                _state.value = PdfState(pageCount = count)
                renderPage(savedPage.coerceIn(0, count - 1))
            } catch (e: Exception) {
                _state.value = PdfState(error = "Cannot open PDF: ${e.message}")
            }
        }
    }

    /** Navigate to a specific page (0-indexed). */
    fun goToPage(page: Int) {
        val current = _state.value
        if (page < 0 || page >= current.pageCount) return
        viewModelScope.launch { renderPage(page) }
    }

    /** Go to the next page. */
    fun nextPage() = goToPage(_state.value.currentPage + 1)

    /** Go to the previous page. */
    fun prevPage() = goToPage(_state.value.currentPage - 1)

    private suspend fun renderPage(pageIndex: Int) {
        val r = renderer ?: return
        val bitmap = withContext(Dispatchers.Default) {
            val page = r.openPage(pageIndex)
            // Render at 2x for sharpness on high-DPI screens
            val scale = 2
            val bmp = Bitmap.createBitmap(
                page.width * scale,
                page.height * scale,
                Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bmp
        }
        _state.value = _state.value.copy(
            currentPage = pageIndex,
            bitmap = bitmap,
            loading = false
        )
        // Save position
        bookId?.let { savePosition(it, pageIndex) }
    }

    /** Save the current page so re-entering the PDF resumes here. */
    private fun savePosition(bookId: String, page: Int) {
        viewModelScope.launch {
            try {
                val app = getApplication<PageTimeApp>()
                val settings = app.container.settingsRepository
                // Reuse the same DataStore key pattern as text offset, namespaced by book id
                settings.savePdfPage(bookId, page)
            } catch (_: Exception) { }
        }
    }

    /** Restore the last-read page, defaulting to 0. */
    private suspend fun restorePosition(bookId: String): Int {
        return try {
            val app = getApplication<PageTimeApp>()
            val settings = app.container.settingsRepository
            settings.getPdfPage(bookId)
        } catch (_: Exception) { 0 }
    }

    override fun onCleared() {
        super.onCleared()
        renderer?.close()
        pfd?.close()
        renderer = null
        pfd = null
    }
}

data class PdfState(
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val bitmap: Bitmap? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

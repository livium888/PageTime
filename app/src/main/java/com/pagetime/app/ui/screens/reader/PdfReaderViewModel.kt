package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
 * the author intended.
 */
class PdfReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PdfState())
    val state = _state.asStateFlow()

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var currentBitmap: Bitmap? = null

    /** Open a PDF file and prepare for reading. */
    fun open(pdfPath: String) {
        if (_state.value.pageCount > 0) return // already open
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
                _state.value = PdfState(pageCount = count)
                renderPage(0)
            } catch (e: Exception) {
                _state.value = PdfState(error = "Cannot open PDF: ${e.message}")
            }
        }
    }

    /** Navigate to a specific page (0-indexed). */
    fun goToPage(page: Int) {
        val current = _state.value
        if (page < 0 || page >= current.pageCount || page == current.currentPage) return
        viewModelScope.launch { renderPage(page) }
    }

    fun nextPage() = goToPage(_state.value.currentPage + 1)
    fun prevPage() = goToPage(_state.value.currentPage - 1)

    private suspend fun renderPage(pageIndex: Int) {
        val r = renderer ?: return
        val oldBitmap = currentBitmap
        val bitmap = withContext(Dispatchers.Default) {
            val page = r.openPage(pageIndex)
            // Render at 2x for sharp text on high-DPI screens
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
        // Recycle the old bitmap to free memory
        oldBitmap?.recycle()
        currentBitmap = bitmap
        _state.value = _state.value.copy(
            currentPage = pageIndex,
            bitmap = bitmap,
            loading = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        currentBitmap?.recycle()
        currentBitmap = null
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

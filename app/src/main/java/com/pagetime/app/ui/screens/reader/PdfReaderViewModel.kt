package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenRepository
import com.pagetime.app.domain.BalanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Manages PDF reading state, reading-time tracking, text extraction,
 * AI-powered flashcard generation via Gemini, and last-position memory.
 *
 * Reading time is credited the same way the EPUB reader does it:
 * every second of foreground reading earns browse balance (or gate credit)
 * via [BalanceManager.earnFromReading].
 */
class PdfReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PdfState())
    val state = _state.asStateFlow()

    private val container = (app as PageTimeApp).container
    private val balanceManager: BalanceManager = container.balanceManager
    private val settingsRepository = container.settingsRepository
    private val lumenRepo: LumenRepository = container.lumenRepository

    // --- Reading timer ---
    private var tickerJob: Job? = null
    private var pendingSeconds = 0
    private var readingStartTime = 0L
    private var currentBookId: String? = null

    // --- AI flashcard state ---
    private val _flashcardState = MutableStateFlow(FlashcardUiState())
    val flashcardState = _flashcardState.asStateFlow()

    fun open(pdfPath: String, bookId: String) {
        if (_state.value.pageCount > 0) return
        currentBookId = bookId
        viewModelScope.launch {
            val file = File(pdfPath)
            if (!file.exists()) {
                _state.value = PdfState(error = "PDF file not found")
                return@launch
            }
            val renderer = PdfRendererHolder.open(getApplication(), pdfPath)
            if (renderer != null) {
                // Restore last read position
                val savedPage = settingsRepository.getPdfPage(bookId)
                _state.value = PdfState(
                    pageCount = renderer.pageCount,
                    currentPage = savedPage.coerceIn(0, renderer.pageCount - 1),
                    loading = false,
                    restoredPage = savedPage.coerceIn(0, renderer.pageCount - 1),
                )
                startReading()
            } else {
                _state.value = PdfState(error = "Cannot open PDF")
            }
        }
    }

    // --- Reading timer ---

    private fun startReading() {
        readingStartTime = SystemClock.elapsedRealtime()
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                pendingSeconds++
                balanceManager.earnFromReading(1L)
            }
        }
    }

    private fun stopReading() {
        tickerJob?.cancel()
        tickerJob = null
        if (pendingSeconds > 0) {
            val seconds = pendingSeconds
            pendingSeconds = 0
            viewModelScope.launch {
                val bookId = currentBookId ?: return@launch
                container.libraryRepository.addReadingSeconds(bookId, seconds.toLong())
            }
        }
    }

    // --- Page tracking ---

    fun markPage(pageIndex: Int) {
        val current = _state.value
        if (pageIndex != current.currentPage) {
            _state.value = current.copy(currentPage = pageIndex)
            val bookId = currentBookId ?: return
            viewModelScope.launch {
                settingsRepository.savePdfPage(bookId, pageIndex)
            }
        }
    }

    fun goToPage(page: Int) {
        val current = _state.value
        if (page in 0 until current.pageCount) {
            _state.value = current.copy(currentPage = page, targetScrollPage = page)
        }
    }

    fun clearTargetScrollPage() {
        _state.value = _state.value.copy(targetScrollPage = null)
    }

    // --- Text extraction ---

    /**
     * Extract text from a specific page using PdfBox.
     * Returns the text content of the page, or null on failure.
     */
    suspend fun extractPageText(pageIndex: Int): String? {
        return withContext(Dispatchers.Default) {
            try {
                val app = getApplication<Application>()
                val bookDao = (app as PageTimeApp).container.database.bookDao()
                val book = currentBookId?.let { bookDao.getById(it) } ?: return@withContext null
                val repo = (app as PageTimeApp).container.libraryRepository
                val pdfFile = repo.pdfSourceFile(book) ?: return@withContext null
                if (!pdfFile.exists()) return@withContext null

                PDFBoxResourceLoader.init(app)
                val doc = PDDocument.load(pdfFile)
                val stripper = PDFTextStripper()
                stripper.startPage = pageIndex + 1 // PDFTextStripper is 1-indexed
                stripper.endPage = pageIndex + 1
                val text = stripper.getText(doc)
                doc.close()
                text.trim()
            } catch (e: Exception) {
                null
            }
        }
    }

    // --- AI-powered flashcard generation ---

    /**
     * One-tap: extract the current page text and send it to Gemini to
     * auto-generate a flashcard. No typing required.
     */
    fun generateFlashcardFromCurrentPage() {
        val bookId = currentBookId ?: return
        val pageIndex = _state.value.currentPage
        _flashcardState.value = FlashcardUiState(generating = true)
        viewModelScope.launch {
            try {
                val app = getApplication<PageTimeApp>()
                val bookDao = app.container.database.bookDao()
                val book = bookDao.getById(bookId)
                if (book == null) {
                    _flashcardState.value = FlashcardUiState(error = "Book not found")
                    return@launch
                }
                val text = extractPageText(pageIndex)
                if (text.isNullOrBlank()) {
                    _flashcardState.value = FlashcardUiState(error = "No text found on this page")
                    return@launch
                }
                // Use the same LumenRepository.draft() that the EPUB reader uses
                val draft = lumenRepo.draft(book, text)
                // Auto-save the card
                val sourceJson = """{"type":"pdf","page":$pageIndex}"""
                lumenRepo.save(
                    book = book,
                    front = draft.front,
                    back = draft.back,
                    quote = draft.quote,
                    sourceLocatorJson = sourceJson,
                    sourceChapterIndex = pageIndex,
                    sourceFraction = pageIndex.toFloat() / (_state.value.pageCount.coerceAtLeast(1)),
                    afterIndex = null,
                )
                val app2 = getApplication<Application>()
                Toast.makeText(
                    app2,
                    "Flashcard created: \"${draft.front.take(50)}\"",
                    Toast.LENGTH_SHORT,
                ).show()
                _flashcardState.value = FlashcardUiState(
                    lastCreatedFront = draft.front,
                    lastCreatedBack = draft.back,
                )
            } catch (e: Exception) {
                _flashcardState.value = FlashcardUiState(
                    error = "Failed to generate flashcard: ${e.message}"
                )
            }
        }
    }

    /**
     * Generate a flashcard from user-selected text (highlight → flashcard).
     */
    fun generateFlashcardFromSelection(selectedText: String) {
        val bookId = currentBookId ?: return
        val pageIndex = _state.value.currentPage
        if (selectedText.isBlank()) return
        _flashcardState.value = FlashcardUiState(generating = true)
        viewModelScope.launch {
            try {
                val app = getApplication<PageTimeApp>()
                val bookDao = app.container.database.bookDao()
                val book = bookDao.getById(bookId)
                if (book == null) {
                    _flashcardState.value = FlashcardUiState(error = "Book not found")
                    return@launch
                }
                val draft = lumenRepo.draft(book, selectedText)
                val sourceJson = """{"type":"pdf","page":$pageIndex}"""
                lumenRepo.save(
                    book = book,
                    front = draft.front,
                    back = draft.back,
                    quote = draft.quote,
                    sourceLocatorJson = sourceJson,
                    sourceChapterIndex = pageIndex,
                    sourceFraction = pageIndex.toFloat() / (_state.value.pageCount.coerceAtLeast(1)),
                    afterIndex = null,
                )
                val app2 = getApplication<Application>()
                Toast.makeText(
                    app2,
                    "Flashcard created: \"${draft.front.take(50)}\"",
                    Toast.LENGTH_SHORT,
                ).show()
                _flashcardState.value = FlashcardUiState(
                    lastCreatedFront = draft.front,
                    lastCreatedBack = draft.back,
                )
            } catch (e: Exception) {
                _flashcardState.value = FlashcardUiState(
                    error = "Failed to generate flashcard: ${e.message}"
                )
            }
        }
    }

    fun dismissFlashcardResult() {
        _flashcardState.value = FlashcardUiState()
    }

    // --- Manual flashcard save (from the selection bottom sheet) ---

    fun saveFlashcard(front: String, back: String, source: String?, pageIndex: Int) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            try {
                val app = getApplication<PageTimeApp>()
                val bookDao = app.container.database.bookDao()
                val book = bookDao.getById(bookId) ?: return@launch
                val sourceJson = """{"type":"pdf","page":$pageIndex}"""
                lumenRepo.save(
                    book = book,
                    front = front,
                    back = back,
                    quote = source ?: front,
                    sourceLocatorJson = sourceJson,
                    sourceChapterIndex = pageIndex,
                    sourceFraction = pageIndex.toFloat() / (_state.value.pageCount.coerceAtLeast(1)),
                    afterIndex = null,
                )
            } catch (e: Exception) {
                // Card save failed silently
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopReading()
        PdfRendererHolder.close()
    }
}

data class PdfState(
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    /** Page to scroll to after the LazyColumn is ready. */
    val restoredPage: Int? = null,
    /** Explicit scroll target from "Go to page" dialog. */
    val targetScrollPage: Int? = null,
)

data class FlashcardUiState(
    val generating: Boolean = false,
    val error: String? = null,
    val lastCreatedFront: String? = null,
    val lastCreatedBack: String? = null,
)

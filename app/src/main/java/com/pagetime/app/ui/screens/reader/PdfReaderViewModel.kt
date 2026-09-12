package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.LumenRepository
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.domain.BalanceManager
import io.github.openspacedrepetition.Card
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
import java.util.UUID

/**
 * Manages PDF reading state, reading-time tracking, text extraction,
 * AI-powered flashcard generation via Gemini, and last-position memory.
 *
 * PDF flashcards are saved as [LearningCardEntity] rows — the same
 * recall/review system the EPUB reader uses — so they appear in the
 * Recall tab with FSRS scheduling, grading, notifications, and time
 * earning via [BalanceManager.earnFromFlashcard].
 *
 * Reading time is credited via [BalanceManager.earnFromReading].
 */
class PdfReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PdfState())
    val state = _state.asStateFlow()

    private val container = (app as PageTimeApp).container
    private val balanceManager: BalanceManager = container.balanceManager
    private val settingsRepository = container.settingsRepository
    private val lumenRepo: LumenRepository = container.lumenRepository
    private val learningCardDao = container.database.learningCardDao()

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
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                val text = stripper.getText(doc)
                doc.close()
                text.trim()
            } catch (e: Exception) {
                null
            }
        }
    }

    // --- AI-powered flashcard generation (Learning Cards) ---

    /**
     * One-tap: extract the current page text, send it to Gemini, and save
     * the result as a Learning Card — immediately due for recall review.
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
                val draft = lumenRepo.draft(book, text)
                saveAsLearningCard(
                    bookId = bookId,
                    front = draft.front,
                    back = draft.back,
                    sourceQuote = draft.quote,
                    pageIndex = pageIndex,
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
     * Generate a flashcard from user-selected text → Learning Card.
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
                saveAsLearningCard(
                    bookId = bookId,
                    front = draft.front,
                    back = draft.back,
                    sourceQuote = draft.quote,
                    pageIndex = pageIndex,
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
     * Saves a card into the learning_cards table (recall system) with
     * FSRS scheduling so it is immediately due for review.
     */
    private suspend fun saveAsLearningCard(
        bookId: String,
        front: String,
        back: String,
        sourceQuote: String,
        pageIndex: Int,
    ) {
        val now = System.currentTimeMillis()
        val fraction = pageIndex.toFloat() / (_state.value.pageCount.coerceAtLeast(1))
        val sourceLocator = """{"type":"pdf","page":$pageIndex}"""
        val fsrsCard = FsrsCardCodec.toJson(Card.builder().build())

        val card = LearningCardEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = pageIndex,
            chapterTitle = null,
            topic = null,
            prompt = front.trim(),
            answer = back.trim(),
            explanation = null,
            sourceLocator = sourceLocator,
            sourceFraction = fraction,
            sourceQuote = sourceQuote.trim(),
            fsrsCardJson = fsrsCard,
            createdAt = now,
            updatedAt = now,
            generatedByAi = true,
            generationKey = null,
            // Immediately due — same as ChapterPromptGenerator.keep()
            status = LearningCardEntity.STATUS_KEPT,
            dueAt = now,
        )
        learningCardDao.upsert(card)
    }

    fun dismissFlashcardResult() {
        _flashcardState.value = FlashcardUiState()
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
    val restoredPage: Int? = null,
    val targetScrollPage: Int? = null,
)

data class FlashcardUiState(
    val generating: Boolean = false,
    val error: String? = null,
    val lastCreatedFront: String? = null,
    val lastCreatedBack: String? = null,
)

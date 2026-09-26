package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.learning.ChapterPromptRules
import com.pagetime.app.data.library.PdfTextCleaner
import com.pagetime.app.data.library.PdfTextExtractor
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
 * Flashcards are generated using [GeminiLearningClient.generateChapterPrompts],
 * the same pipeline the EPUB reader uses for proper Q/A flashcards with
 * questions, answers, and explanations — NOT the Lumen slip-box pipeline.
 *
 * Reading time is credited via [BalanceManager.earnFromReading].
 */
class PdfReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PdfState())
    val state = _state.asStateFlow()

    private val container = (app as PageTimeApp).container
    private val balanceManager: BalanceManager = container.balanceManager
    private val settingsRepository = container.settingsRepository
    private val geminiClient = container.geminiLearningClient
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

                val rawPages = PdfTextExtractor(app).pages(pdfFile)
                PdfTextCleaner.cleanForFlashcards(rawPages, pageIndex)
                    .takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }

    // --- Flashcard preview (two-step flow) ---

    /**
     * Step 1: Extract text from the current page and show it for review.
     * The user sees exactly what will be sent to Gemini and can edit it
     * before confirming generation.
     */
    fun prepareFlashcardPreview() {
        val pageIndex = _state.value.currentPage
        _flashcardState.value = FlashcardUiState(previewing = true)
        viewModelScope.launch {
            val text = extractPageText(pageIndex)
            if (text.isNullOrBlank()) {
                _flashcardState.value = FlashcardUiState(error = "No text found on this page")
                return@launch
            }
            _flashcardState.value = FlashcardUiState(
                previewing = true,
                previewText = text,
                previewSource = PreviewSource.PAGE,
            )
        }
    }

    /**
     * Step 1b: Prepare preview from user-selected text.
     */
    fun prepareFlashcardPreviewFromSelection(selectedText: String) {
        if (selectedText.isBlank()) return
        val cleaned = PdfTextCleaner.cleanForFlashcards(selectedText)
        if (cleaned.isBlank()) {
            _flashcardState.value = FlashcardUiState(
                error = "The selected text looks like document clutter. Select a passage from the main text instead."
            )
            return
        }
        _flashcardState.value = FlashcardUiState(
            previewing = true,
            previewText = cleaned,
            previewSource = PreviewSource.SELECTION,
        )
    }

    /**
     * Step 2: User confirmed (and possibly edited) the text.
     * Send it to Gemini and save the flashcards.
     */
    fun confirmFlashcardGeneration(editedText: String) {
        val bookId = currentBookId ?: return
        val pageIndex = _state.value.currentPage
        if (editedText.isBlank()) {
            _flashcardState.value = FlashcardUiState(error = "Text is empty")
            return
        }
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
                val prompts = geminiClient.generateChapterPrompts(
                    bookTitle = book.title,
                    chapterTitle = "Page ${pageIndex + 1}",
                    passages = listOf(editedText),
                    insist = true,
                )
                // The chapter path runs every response through these strict
                // local checks; this direct PDF path must not bypass them.
                val accepted = ChapterPromptRules.sift(prompts, listOf(editedText)).accepted
                if (accepted.isEmpty()) {
                    _flashcardState.value = FlashcardUiState(
                        error = "Gemini's questions did not pass the quality checks for this text. Try a cleaner or more complete passage."
                    )
                    return@launch
                }
                for (raw in accepted.take(3)) {
                    saveAsLearningCard(
                        bookId = bookId,
                        prompt = raw.prompt,
                        answer = raw.answer,
                        explanation = raw.explanation,
                        sourceQuote = raw.sourceQuote,
                        cardType = if (raw.isCloze) LearningCardEntity.TYPE_CLOZE else LearningCardEntity.TYPE_QA,
                        pageIndex = pageIndex,
                    )
                }
                Toast.makeText(
                    getApplication<Application>(),
                    "${accepted.size.coerceAtMost(3)} flashcard${if (accepted.size > 1) "s" else ""} created from page ${pageIndex + 1}",
                    Toast.LENGTH_SHORT,
                ).show()
                _flashcardState.value = FlashcardUiState(
                    lastCreatedFront = accepted.first().prompt,
                    lastCreatedBack = accepted.first().answer,
                )
            } catch (e: Exception) {
                _flashcardState.value = FlashcardUiState(
                    error = "Failed to generate flashcard: ${e.message}"
                )
            }
        }
    }

    fun dismissFlashcardPreview() {
        _flashcardState.value = FlashcardUiState()
    }

    fun dismissFlashcardResult() {
        _flashcardState.value = FlashcardUiState()
    }

    // --- Legacy methods kept for TextSelectionSheet compatibility ---

    /**
     * Generate a flashcard from user-selected text → Learning Card.
     * Now routes through preview flow.
     */
    fun generateFlashcardFromSelection(selectedText: String) {
        prepareFlashcardPreviewFromSelection(selectedText)
    }

    /**
     * Saves a card into the learning_cards table (recall system) with
     * FSRS scheduling so it is immediately due for review.
     */
    private suspend fun saveAsLearningCard(
        bookId: String,
        prompt: String,
        answer: String,
        explanation: String,
        sourceQuote: String,
        cardType: String,
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
            prompt = prompt.trim(),
            answer = answer.trim(),
            explanation = explanation.trim().takeIf { it.isNotBlank() },
            sourceLocator = sourceLocator,
            sourceFraction = fraction,
            sourceQuote = sourceQuote.trim(),
            cardType = cardType,
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

enum class PreviewSource { PAGE, SELECTION }

data class FlashcardUiState(
    val generating: Boolean = false,
    val previewing: Boolean = false,
    val previewText: String? = null,
    val previewSource: PreviewSource? = null,
    val error: String? = null,
    val lastCreatedFront: String? = null,
    val lastCreatedBack: String? = null,
)

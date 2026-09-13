package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.learning.ChapterPromptRules
import com.pagetime.app.data.learning.RawPrompt
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
     * One-tap: extract the current page text, send it to Gemini via the
     * chapter prompt pipeline (same as EPUB reader), and save the result
     * as a Learning Card — immediately due for recall review.
     *
     * This uses [GeminiLearningClient.generateChapterPrompts] which produces
     * proper Q/A flashcards with questions, answers, and explanations —
     * NOT the Lumen slip-box pipeline.
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
                // Use the Gemini chapter prompt pipeline for proper Q/A flashcards
                val prompts = geminiClient.generateChapterPrompts(
                    bookTitle = book.title,
                    chapterTitle = "Page ${pageIndex + 1}",
                    passages = listOf(text),
                    insist = true,
                )
                // The model's output is checked before any of it is saved.
                //
                // Nothing here used to be checked: this path called the writer
                // directly and wrote every prompt it returned straight into the
                // database, which is how a quote that is not on the page, a
                // question with its own answer inside it, and a cloze with no
                // deletion in it all became scheduled reviews. A card is
                // rehearsed for months, so a wrong one installs a falsehood on
                // purpose — the same rules that guard the chapter pipeline
                // guard this one.
                val accepted = checkPromptsAgainstText(prompts, text)
                if (accepted.isEmpty()) {
                    _flashcardState.value = FlashcardUiState(
                        error = if (prompts.isEmpty()) {
                            "Gemini could not generate a flashcard from this page. " +
                                "Try a page with more content."
                        } else {
                            "${prompts.size} question${if (prompts.size == 1) "" else "s"} came " +
                                "back and none passed the checks — usually a quote that is not " +
                                "actually on the page. Nothing was saved."
                        }
                    )
                    return@launch
                }
                // Save each generated prompt as a Learning Card
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

    /**
     * Generate a flashcard from user-selected text → Learning Card.
     * Uses the same Gemini pipeline as above.
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
                val prompts = geminiClient.generateChapterPrompts(
                    bookTitle = book.title,
                    chapterTitle = "Page ${pageIndex + 1}",
                    passages = listOf(selectedText),
                    insist = true,
                )
                val accepted = checkPromptsAgainstText(prompts, selectedText)
                if (accepted.isEmpty()) {
                    _flashcardState.value = FlashcardUiState(
                        error = if (prompts.isEmpty()) {
                            "Gemini could not generate a flashcard from this text."
                        } else {
                            "${prompts.size} question${if (prompts.size == 1) "" else "s"} came " +
                                "back and none passed the checks — usually a quote that is not " +
                                "actually in the selection. Nothing was saved."
                        }
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
                    "${accepted.size.coerceAtMost(3)} flashcard${if (accepted.size > 1) "s" else ""} created",
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

    /**
     * The prompts fit to be remembered, out of what the model returned.
     *
     * The passage is the text the prompts were written from, and it is what
     * every check is made against: a supporting quote has to be in it, an
     * explanation has to say something it does not already say, a cloze has to
     * be a sentence of it with one deletion. A prompt that fails is dropped
     * rather than corrected — the reader pays for a card once and rehearses it
     * for months, so fewer is the cheap mistake.
     */
    private fun checkPromptsAgainstText(prompts: List<RawPrompt>, passage: String): List<RawPrompt> =
        ChapterPromptRules.sift(
            // One passage, so every prompt can only have come from it. An index
            // the model got wrong — a 1-based count, most often — is normalised
            // rather than throwing the whole answer away; the quote is still
            // checked against the page itself, which is the check that decides
            // whether the question is grounded in anything.
            prompts.map { if (it.passageIndex == 0) it else it.copy(passageIndex = 0) },
            listOf(passage),
        ).accepted

    /**
     * Saves a card into the learning_cards table (recall system) with
     * FSRS scheduling so it is immediately due for review.
     *
     * Uses proper fields: prompt, answer, explanation, sourceQuote, cardType.
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
            // A PDF page is this reader's unit of text, so it is the card's
            // chapter. Left null, every card from every PDF showed no source
            // at all in the Flashcards list.
            chapterTitle = "Page ${pageIndex + 1}",
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

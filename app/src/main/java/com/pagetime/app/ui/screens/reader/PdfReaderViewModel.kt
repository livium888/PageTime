package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.domain.BalanceManager
import io.github.openspacedrepetition.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * Forgets the position the document was opened at.
     *
     * [PdfState.restoredPage] is set once, when the PDF opens, and its whole
     * job is to put the reader back where they left the book. It is NOT where
     * they are now — the list's own scroll state is, and that is what Android
     * hands back after a rotation. Left set, this restore ran again on every
     * recreation and dragged the reader back to the page the book happened to
     * open on, so turning the phone threw the reading away and turning it back
     * did it a second time.
     */
    fun clearRestoredPage() {
        _state.value = _state.value.copy(restoredPage = null)
    }

    /**
     * The shape of each page, remembered for the session.
     *
     * A page is rendered off the main thread, and until its bitmap arrives the
     * list gives the slot a default A4 shape. Rotating the device throws the
     * composition away, so without this every page above the reader would
     * briefly claim a height that is not its own, the list would re-measure
     * its scroll offset against those heights, and the reader would come back
     * somewhere else on the page than they left.
     *
     * The value is the page's width divided by its height — the direction
     * `Modifier.aspectRatio` takes, so a taller-than-wide page comes out below
     * 1. Held the other way round it laid every page out at half the height
     * its bitmap needed. Small, never persisted, and discarded with the
     * ViewModel.
     */
    private val _pageRatios = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val pageRatios: StateFlow<Map<Int, Float>> = _pageRatios.asStateFlow()

    fun recordPageRatio(pageIndex: Int, ratio: Float) {
        if (ratio <= 0f || _pageRatios.value[pageIndex] == ratio) return
        _pageRatios.value = _pageRatios.value + (pageIndex to ratio)
    }

    /**
     * Where the reader is now: which page, and how far down it.
     *
     * Not the same thing as [PdfState.restoredPage], which is only where the
     * book was OPENED and is cleared as soon as it has been honoured. This
     * follows the reader, and exists for one reason: rotation. Android hands a
     * list back its scroll offset in pixels, and a page's height depends on
     * the screen's width, so the offset that was halfway down a page in
     * portrait points somewhere else entirely in landscape. A fraction of the
     * page does not move when the phone does.
     *
     * Held here because the ViewModel is the one thing in this screen that
     * survives a rotation, and read by the screen at the moment the new layout
     * appears. Deliberately not a StateFlow: nothing recomposes on it, it is
     * read once, on the way in.
     */
    private var readingAnchor: ReadingAnchor? = null

    fun currentReadingAnchor(): ReadingAnchor? = readingAnchor

    fun recordReadingAnchor(page: Int, fraction: Float) {
        val next = ReadingAnchor(page, ReaderPositionPolicy.clampFraction(fraction))
        if (readingAnchor == next) return
        readingAnchor = next
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
                // The same generator the EPUB reader uses, so a PDF page gets
                // the same quality checks, the same reader-editable
                // instructions, and the same usage row. Calling the model
                // client directly is what made this the one path the usage
                // screen could not see, and the one path a tailored prompt
                // could not reach.
                //
                // Nothing is saved before it passes the checks. This path used
                // to write every prompt the model returned straight into the
                // database, which is how a quote that is not on the page, a
                // question with its own answer inside it, and a cloze with no
                // deletion in it all became scheduled reviews. A card is
                // rehearsed for months, so a wrong one installs a falsehood on
                // purpose — the rules that guard the chapter pipeline guard
                // this one too, inside the generator.
                val verdict = app.container.chapterPromptGenerator.promptsForText(
                    book = book,
                    chapterTitle = "Page ${pageIndex + 1}",
                    text = text,
                )
                val accepted = verdict.accepted
                val offered = accepted.size + verdict.rejected.size
                if (accepted.isEmpty()) {
                    _flashcardState.value = FlashcardUiState(
                        error = if (offered == 0) {
                            "Gemini could not generate a flashcard from this page. " +
                                "Try a page with more content."
                        } else {
                            "$offered question${if (offered == 1) "" else "s"} came " +
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
                val verdict = app.container.chapterPromptGenerator.promptsForText(
                    book = book,
                    chapterTitle = "Page ${pageIndex + 1}",
                    text = selectedText,
                )
                val accepted = verdict.accepted
                val offered = accepted.size + verdict.rejected.size
                if (accepted.isEmpty()) {
                    _flashcardState.value = FlashcardUiState(
                        error = if (offered == 0) {
                            "Gemini could not generate a flashcard from this text."
                        } else {
                            "$offered question${if (offered == 1) "" else "s"} came " +
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

/**
 * How far into the document the reader is: which page, and how far down it.
 *
 * The fraction is of the whole list item rather than of the page artwork,
 * because the item is what the list scrolls. The item is a page plus the
 * page-start marker above it, and the marker's height is fixed, which is what
 * makes the fraction transferable: the part of the item that scales with the
 * screen is the page, and it scales by the same amount everywhere.
 */
data class ReadingAnchor(val page: Int, val fraction: Float)

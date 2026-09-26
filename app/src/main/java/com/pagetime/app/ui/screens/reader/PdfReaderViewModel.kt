package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.library.PdfTextCleaner
import com.pagetime.app.data.library.PdfTextExtractor
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.domain.BalanceManager
import com.pagetime.app.domain.ReadingMomentum
import io.github.openspacedrepetition.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Flashcards are generated using [ChapterPromptGenerator.promptsForText],
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

    /** Surfaced once when a reading-momentum bonus lands; see [ReadingMomentum]. */
    private val _momentumNotice = MutableStateFlow<String?>(null)
    val momentumNotice = _momentumNotice.asStateFlow()

    private var creditedSecondsSinceMomentumBonus = 0L
    private var nextMomentumThreshold = ReadingMomentum.nextThresholdSeconds()

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
                    // Read in the same pass as the page, so the first frame is
                    // already the theme the reader chose. Coming from a
                    // separate flow made the page flash the other way round on
                    // the way in.
                    pdfDarkMode = settingsRepository.pdfDarkMode(),
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
                creditedSecondsSinceMomentumBonus++
                if (ReadingMomentum.shouldFire(creditedSecondsSinceMomentumBonus, nextMomentumThreshold)) {
                    creditedSecondsSinceMomentumBonus = 0
                    nextMomentumThreshold = ReadingMomentum.nextThresholdSeconds()
                    launch { awardMomentumBonus() }
                }
            }
        }
    }

    /** Pays and announces a reading-momentum bonus; a no-op if the reward is off. */
    private suspend fun awardMomentumBonus() {
        val seconds = balanceManager.earnReadingMomentumBonus()
        if (seconds <= 0) return
        _momentumNotice.value = "Reading momentum — +${seconds}s of app time"
        delay(6_000)
        _momentumNotice.value = null
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

    /**
     * Records the page the reader is on.
     *
     * Called with the page at the top of the viewport, and deliberately NOT
     * when a page becomes composed. A lazy list builds the items it is about
     * to need, several pages beyond the reader, so counting builds made the
     * page counter race forward on a flick — 30, 33, back to 30 — and it
     * aimed both "flashcard from this page" and the saved resume position at a
     * page nobody was looking at.
     */
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
                // Re-clean user edits as well as extracted text: the preview
                // must not turn document furniture into a source for cards.
                val text = PdfTextCleaner.cleanForFlashcards(editedText)
                if (text.isBlank()) {
                    _flashcardState.value = FlashcardUiState(
                        error = "The text looks like document clutter. Edit it to include a passage from the main text."
                    )
                    return@launch
                }
                // Use the same locally-sifted generation path as EPUB. It also
                // records usage and honours the reader's prompt instructions.
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
                            "Gemini could not generate a flashcard from this text. Try a cleaner or more complete passage."
                        } else {
                            "$offered question${if (offered == 1) "" else "s"} came " +
                                "back and none passed the checks. Nothing was saved."
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

    /** Legacy selection entry point; all generation now uses the editable preview. */
    fun generateFlashcardFromSelection(selectedText: String) {
        prepareFlashcardPreviewFromSelection(selectedText)
    }

    /** Shows a page-text sheet's content in the editable preview before generation. */
    fun generateFlashcardFromText(pageText: String) {
        val cleaned = PdfTextCleaner.cleanForFlashcards(pageText)
        if (cleaned.isBlank()) {
            _flashcardState.value = FlashcardUiState(
                error = "No readable main text was found on this page."
            )
            return
        }
        _flashcardState.value = FlashcardUiState(
            previewing = true,
            previewText = cleaned,
            previewSource = PreviewSource.PAGE,
        )
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

    /**
     * Remembers the PDF page theme.
     *
     * It has to outlive the screen. The toggle used to be `rememberSaveable`
     * and nothing else, so it survived a rotation and was forgotten the moment
     * the reader went back to the library — which meant re-enabling dark mode
     * every single time a PDF was opened.
     */
    fun setPdfDarkMode(dark: Boolean) {
        viewModelScope.launch { settingsRepository.setPdfDarkMode(dark) }
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
    /**
     * The reader's stored page theme, or null when they have never chosen one.
     *
     * Null means the screen should follow the system rather than assume light.
     * The two are different: false is "I want a white page", which is a choice
     * worth honouring in a dark room.
     */
    val pdfDarkMode: Boolean? = null,
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

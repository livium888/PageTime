package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ReaderSettings
import com.pagetime.app.data.local.LearningCheckpoint
import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.CaptureDiagnostic
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.LumenConnections
import com.pagetime.app.data.LumenDraft
import com.pagetime.app.data.asAnswer
import com.pagetime.app.data.local.LumenCardEntity
import com.pagetime.app.data.local.PagemarkEntity
import com.pagetime.app.data.PagemarkSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import com.pagetime.app.data.LumenDraftSource
import com.pagetime.app.data.learning.ChapterPromptGenerator
import com.pagetime.app.data.learning.PromptSurfacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File

class ReaderViewModel(private val app: Application, private val bookId: String) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository
    private val balanceManager = container.balanceManager
    private val settingsRepository = container.settingsRepository
    private val readium = container.readiumEngine

    // App-lifetime scope for persistence writes. viewModelScope is cancelled the
    // moment this screen is left — launching the "save position" write there meant
    // it never ran when the user exited the book.
    private val persistenceScope = container.scope

    private val guard = ReadingGuard()

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book = _book.asStateFlow()

    private val _textContent = MutableStateFlow<String?>(null)
    val textContent = _textContent.asStateFlow()

    private val _initialTextFraction = MutableStateFlow(0f)
    val initialTextFraction = _initialTextFraction.asStateFlow()

    private val _initialTextOffset = MutableStateFlow(0)
    val initialTextOffset = _initialTextOffset.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _sessionSeconds = MutableStateFlow(0L)
    val sessionSeconds = _sessionSeconds.asStateFlow()

    private val _creditedSeconds = MutableStateFlow(0L)
    val creditedSeconds = _creditedSeconds.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _guardState = MutableStateFlow(ReadingGuard.State())
    val guardState = _guardState.asStateFlow()

    /** The parsed Readium publication for EPUB books; null while loading / for txt. */
    private val _publication = MutableStateFlow<Publication?>(null)
    val publication = _publication.asStateFlow()


    /**
     * Saved reading position as a Readium Locator JSON string, restored by the
     * navigator on creation. This is an exact position (resource + offset), not an
     * approximate scroll fraction — the whole point of moving to Readium.
     */
    private val _initialLocatorJson = MutableStateFlow<String?>(null)
    val initialLocatorJson = _initialLocatorJson.asStateFlow()

    /** Distinguishes "no saved locator" from "saved locator lookup still loading". */
    private val _initialLocatorReady = MutableStateFlow(false)
    val initialLocatorReady = _initialLocatorReady.asStateFlow()

    private val _bookmarkPresent = MutableStateFlow(false)
    val bookmarkPresent = _bookmarkPresent.asStateFlow()

    private val _checkpointPresent = MutableStateFlow(false)
    val checkpointPresent = _checkpointPresent.asStateFlow()

    private val _enhancing = MutableStateFlow(false)
    val enhancing = _enhancing.asStateFlow()

    private val _enhancementProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val enhancementProgress = _enhancementProgress.asStateFlow()

    val readerSettings = settingsRepository.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    /** Concepts already discovered for this book; used only by the EPUB hint layer. */
    val conceptMap = container.conceptMapRepository.observeBookMap(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConceptMap(emptyList(), emptyList()))


    val balanceSeconds = balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var tickerJob: Job? = null

    /** Whether the guard has been started for this book, so resumes resume. */
    private var guardStarted = false
    private var pendingSeconds = 0L
    private var resumed = false

    /** Latest position reported by the navigator (EPUB only). */
    @Volatile
    private var latestLocator: org.readium.r2.shared.publication.Locator? = null

    private var locatorSaveJob: Job? = null
    private var txtSaveJob: Job? = null

    private val _resumeNotice = MutableStateFlow<String?>(null)
    val resumeNotice = _resumeNotice.asStateFlow()

    private val _mapMoment = MutableStateFlow<com.pagetime.app.data.local.MapMoment?>(null)
    val mapMoment = _mapMoment.asStateFlow()

    /** No position writes are allowed until the initial restore has completed. */
    @Volatile
    private var locatorRestoreComplete = false

    /**
     * Whether a cloud rewrite can be offered at all. Read once, off the main
     * thread, because the key lives in DataStore.
     */
    private val _geminiAvailable = MutableStateFlow(false)
    val geminiAvailable: StateFlow<Boolean> = _geminiAvailable.asStateFlow()

    init {
        loadBook()
        // Off the main thread: the key lives in DataStore, and the dialog only
        // needs the answer by the time a card exists to rewrite.
        viewModelScope.launch {
            _geminiAvailable.value =
                runCatching { container.geminiLearningClient.hasKey() }.getOrDefault(false)
        }
    }

    fun retry() {
        _error.value = null
        _textContent.value = null
        _initialTextFraction.value = 0f
        _initialTextOffset.value = 0
        _publication.value = null
        _initialLocatorJson.value = null
        _initialLocatorReady.value = false
        _bookmarkPresent.value = false
        locatorRestoreComplete = false
        txtRestoreComplete = false
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val loaded = if (bookId == "last") repo.getMostRecentBook() else repo.getBook(bookId)
            _book.value = loaded
            loaded?.let { persistenceScope.launch { settingsRepository.setLastReadBookId(it.id) } }
            if (loaded == null) {
                _error.value = "Book not found in library"
                return@launch
            }
            // The lifecycle may have already reached ON_RESUME before the book
            // finished loading; make sure the timer actually starts.
            if (resumed) tryStartTicker()

            _bookmarkPresent.value = if (loaded.format == "epub") {
                settingsRepository.savedBookmarkLocator(loaded.id) != null
            } else {
                settingsRepository.savedBookmarkScroll(loaded.id) != null
            }
            _checkpointPresent.value = settingsRepository.learningCheckpoint() != null

            val pendingSource = settingsRepository.consumePendingReaderSource(loaded.id)
            if (loaded.format == "epub") {
                withContext(Dispatchers.IO) {
                    openEpub(loaded, pendingSource?.locatorJson)
                }
            } else {
                // Seed from the DB before publishing content so the Compose effect
                // cannot observe text before the saved fraction is available.
                val savedOffset = settingsRepository.savedTextOffset(loaded.id) ?: 0
                latestTxtFraction = (pendingSource?.fraction ?: loaded.scrollProgress).coerceIn(0f, 1f)
                _initialTextFraction.value = latestTxtFraction
                _initialTextOffset.value = savedOffset
                txtRestoreComplete = false
                withContext(Dispatchers.IO) {
                    _textContent.value = runCatching { File(loaded.localPath).readText() }.getOrNull()
                        ?: run {
                            _error.value = "Cannot read this book's file."
                            null
                        }
                }
                txtRestoreComplete = latestTxtFraction <= 0f
            }
        }
    }

    private suspend fun openEpub(book: BookEntity, pendingLocatorJson: String?) {
        // Read the saved position BEFORE publishing the publication: the navigator
        // host is created the moment `_publication` flips non-null and must have the
        // locator at that instant to restore the exact reading spot.
        val savedLocatorJson = pendingLocatorJson ?: settingsRepository.savedLocator(book.id)
        // try/catch instead of runCatching: retrieve/open are suspend functions and
        // runCatching's lambda is not a suspend context.
        // getOrNull instead of getOrThrow: Readium's Try failure wraps an Error, not
        // a Throwable, and that getOrThrow overload is deprecated at ERROR level.
        val publication = try {
            val asset = readium.assetRetriever.retrieve(
                File(book.localPath),
                MediaType.EPUB
            ).getOrNull() ?: error("Could not read the book file")
            readium.publicationOpener.open(
                asset,
                allowUserInteraction = false
            ).getOrNull() ?: error("Could not open this EPUB")
        } catch (t: Throwable) {
            _error.value = "Cannot open this EPUB: ${t.message}"
            return
        }
        locatorRestoreComplete = savedLocatorJson == null
        _initialLocatorJson.value = savedLocatorJson
        _initialLocatorReady.value = true
        _publication.value = publication
    }

    fun startReading() {
        resumed = true
        tryStartTicker()
    }

    fun stopReading() {
        resumed = false
        tickerJob?.cancel()
        tickerJob = null
        persistPositionNow()
        flush()
    }

    private fun tryStartTicker() {
        // The book loads asynchronously on first open; only arm the ticker once it
        // exists AND the reader is actually in the foreground.
        val book = _book.value ?: return
        if (tickerJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        // Started once per book; every later return to the foreground resumes.
        // These used to be the same call, which handed out a fresh allowance and
        // a cleared watermark on every flick to the home screen.
        if (guardStarted) {
            guard.resume(now)
        } else {
            guard.start(now, book.scrollProgress)
            guardStarted = true
        }
        _guardState.value = guard.state
        tickerJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                ticks++
                val now = SystemClock.elapsedRealtime()
                // onTick decides whether THIS second is creditable: foreground,
                // recently active, plausible pace, and anti-oscillation budget left.
                if (guard.onTick(now)) {
                    pendingSeconds++
                    _creditedSeconds.value++
                }
                _sessionSeconds.value++
                _guardState.value = guard.state
                if (ticks % 5 == 0) {
                    flush()
                    // Periodic position checkpoint: if the OS kills the process,
                    // we lose at most ~5s of reading progress instead of all of it.
                    persistPositionNow()
                }
            }
        }
    }

    private fun flush() {
        if (pendingSeconds <= 0) return
        val seconds = pendingSeconds
        pendingSeconds = 0
        val currentBook = _book.value ?: return
        // persistenceScope, not viewModelScope: stopReading() runs during teardown
        // and the pending seconds must still be written after the scope is cancelled.
        persistenceScope.launch {
            balanceManager.earnFromReading(seconds)
            repo.addReadingSeconds(currentBook.id, seconds)
        }
    }

    fun onUserScrolled() {
        guard.onMovement(SystemClock.elapsedRealtime())
        _guardState.value = guard.state
    }

    fun onProgressChanged(progress: Float) {
        _progress.value = progress.coerceIn(0f, 1f)
        guard.onProgress(_progress.value, SystemClock.elapsedRealtime())
        _guardState.value = guard.state
    }

    private fun currentChapterIndex(): Int? {
        val locator = latestLocator ?: return null
        val publication = _publication.value ?: return null
        return publication.readingOrder.indexOfFirstWithHref(locator.href)
    }

    /** Updates the concept map when the chapter changes. */
    private fun updateConceptMap(chapterIndex: Int) {
        val b = _book.value ?: return
        persistenceScope.launch {
            try {
                val mapResult = container.conceptMapRepository.generateForReadingWindow(
                    bookId = b.id,
                    chapterIndex = chapterIndex
                )
                _mapMoment.value = com.pagetime.app.data.local.MapMoment(
                    bookId = b.id,
                    chapterIndex = chapterIndex,
                    conceptCount = mapResult.concepts.size,
                    relationshipCount = mapResult.relationships.size,
                    featuredConcept = mapResult.relationships.firstOrNull()?.sourceLabel
                        ?: mapResult.concepts.firstOrNull()?.label,
                    featuredRelationship = mapResult.relationships.firstOrNull()?.let {
                        "${it.relationType} ${it.targetLabel}"
                    },
                    createdAt = System.currentTimeMillis()
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun resumeAfterIdle() {
        guard.onContinueTapped(SystemClock.elapsedRealtime())
        _guardState.value = guard.state
    }

    /** Plain-text reader's current scroll fraction (0..1 of the whole book). */
    @Volatile
    private var latestTxtFraction: Float = 0f

    /** Exact character offset of the current plain-text page's first char. */
    @Volatile
    private var latestTxtPageOffset: Int = 0

    /** Exact end offset (exclusive) of the current plain-text page. */
    @Volatile
    private var latestTxtPageEndOffset: Int = 0

    /** No writes until the Compose scroll restore has applied the saved fraction. */
    @Volatile
    private var txtRestoreComplete = false

    fun markTxtRestoreComplete() {
        if (txtRestoreComplete) return
        txtRestoreComplete = true
        _progress.value = latestTxtFraction
        showResumeNotice(latestTxtFraction)
    }

    /** Updates text progress only after the initial page has been restored. */
    fun onTextPageChanged(
        pageIndex: Int,
        pageCount: Int,
        userInitiated: Boolean,
        pageStartOffset: Int = 0,
        pageEndOffset: Int = 0
    ) {
        if (pageCount <= 0) return
        latestTxtPageOffset = pageStartOffset
        latestTxtPageEndOffset = pageEndOffset
        val fraction = TextPageLayout.fractionForPage(pageIndex, pageCount)
        _progress.value = fraction
        if (!userInitiated || !txtRestoreComplete) return
        // Progress, not merely movement.
        //
        // This was onUserScrolled(), which tells the guard someone is awake but
        // mints no budget — budget comes only from forward content. So a
        // plain-text book earned the 120-second opening allowance and then
        // nothing, ever, however long the reader stayed. Only EPUBs reported
        // progress, through the Readium locator.
        //
        // It hid behind a second bug: the guard used to restart on every
        // ON_RESUME, handing out a fresh allowance each time the reader flicked
        // away and back, which looked enough like earning to pass for it. Fixing
        // that stopped the drip and left the counter stuck at two minutes.
        onProgressChanged(fraction)
        updateScrollProgress(fraction)
        persistenceScope.launch {
            settingsRepository.saveTextOffset(bookId, pageStartOffset)
        }
    }

    fun currentLearningPosition(): Pair<String?, Int?> =
        if (_book.value?.format == "epub") {
            latestLocator?.toJSON()?.toString() to null
        } else {
            null to latestTxtOffset()
        }

    // region Incremental reading (pagemarks)

    private val pagemarkRepo = container.pagemarkRepository

    /** Every chunk of this book, oldest first, for the Options menu and queue. */
    val pagemarks = pagemarkRepo.observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun activeChunk(): PagemarkEntity? =
        pagemarks.value.firstOrNull { it.state == PagemarkSession.State.READING.name }

    /** The chapter the reader is inside, to name the chunk after it. */
    private fun currentChapterTitle(): String? {
        val publication = _publication.value ?: return null
        val locator = latestLocator ?: return null
        val index = publication.readingOrder.indexOfFirstWithHref(locator.href)
        if (index < 0) return null
        return publication.readingOrder[index].title
    }

    private fun currentFraction(): Float {
        val book = _book.value ?: return _progress.value
        return if (book.format == "epub") {
            (latestLocator?.locations?.progression?.toFloat() ?: _progress.value).coerceIn(0f, 1f)
        } else {
            latestTxtFraction
        }
    }

    private fun currentLocatorJson(): String? {
        val book = _book.value ?: return null
        return if (book.format == "epub") latestLocator?.toJSON()?.toString() else null
    }

    /**
     * Starts a chunk at the current position. An already-open chunk is
     * suspended silently — the reader moved on, they did not finish.
     */
    fun startChunkHere() {
        if (_book.value == null) return
        viewModelScope.launch {
            pagemarkRepo.startChunk(
                bookId = bookId,
                startLocatorJson = currentLocatorJson(),
                startFraction = currentFraction(),
                title = currentChapterTitle()
            )
        }
    }

    /** Closes the open chunk at the current position with a rating. */
    fun closeChunkHere(rating: Int) {
        val chunk = activeChunk() ?: return
        viewModelScope.launch {
            pagemarkRepo.closeChunk(
                id = chunk.id,
                rating = rating,
                endLocatorJson = currentLocatorJson(),
                endFraction = currentFraction()
            )
        }
    }

    /** Pauses the open chunk without judging it. */
    fun suspendChunkHere() {
        val chunk = activeChunk() ?: return
        viewModelScope.launch { pagemarkRepo.suspendChunk(chunk.id) }
    }

    // endregion

    // region Text highlights

    private val highlightRepo = container.highlightRepository

    /** Every highlight of this book, for rendering and management. */
    val highlights = highlightRepo.observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The pending plain-text highlight start (whole-book char offset), or null.
     *
     * Set by "Start highlight here", consumed by "End highlight here". The
     * two-tap anchor is what lets a highlight cross any number of pages in a
     * paged reader that has no drag selection.
     */
    private val _pendingTxtHighlightStart = MutableStateFlow<Int?>(null)
    val pendingTxtHighlightStart = _pendingTxtHighlightStart.asStateFlow()

    /** Starts a plain-text highlight at the current page's start. */
    fun startHighlightHere() {
        val text = _textContent.value ?: return
        if (text.isEmpty()) return
        _pendingTxtHighlightStart.value = latestTxtPageOffset.coerceIn(0, text.length)
    }

    /** Ends the pending plain-text highlight at the current page's end. */
    fun endHighlightHere() {
        val text = _textContent.value ?: return
        val start = _pendingTxtHighlightStart.value ?: return
        _pendingTxtHighlightStart.value = null
        val end = latestTxtPageEndOffset.coerceIn(start + 1, text.length)
        viewModelScope.launch {
            highlightRepo.createTxtSpan(bookId, start, end, text)
        }
    }

    /** Abandons the pending highlight start. */
    fun clearPendingHighlight() {
        _pendingTxtHighlightStart.value = null
    }

    /** Marks the current Readium selection as a persistent highlight. */
    fun saveEpubHighlight(locatorJson: String?, text: String) {
        if (locatorJson.isNullOrBlank()) return
        viewModelScope.launch {
            highlightRepo.createEpubSpan(bookId, locatorJson, text)
        }
    }

    fun deleteHighlight(id: String) {
        viewModelScope.launch { highlightRepo.delete(id) }
    }

    // endregion

    // region Lumen cards

    private val lumenRepo = container.lumenRepository

    private val _lumenDraft = MutableStateFlow<LumenDraft?>(null)
    val lumenDraft = _lumenDraft.asStateFlow()

    private val _lumenCapturing = MutableStateFlow(false)
    val lumenCapturing = _lumenCapturing.asStateFlow()

    /** The term being explained, and the answer once it lands. */
    /** The finished answer about a selection: a word explained, or a rewrite. */
    private val _answer = MutableStateFlow<com.pagetime.app.data.ReaderAnswer?>(null)
    val answer = _answer.asStateFlow()

    /** The heading to show while an answer is still being written. */
    private val _answering = MutableStateFlow<String?>(null)
    val answering = _answering.asStateFlow()

    private val _answerError = MutableStateFlow<String?>(null)
    val answerError = _answerError.asStateFlow()

    private val _captureDiagnostic = MutableStateFlow<CaptureDiagnostic.Record?>(null)
    val captureDiagnostic = _captureDiagnostic.asStateFlow()



    /** Where the freshly captured card could continue the line (box 1). */
    private val _lumenFileSuggestions = MutableStateFlow<List<LumenCardEntity>>(emptyList())
    val lumenFileSuggestions = _lumenFileSuggestions.asStateFlow()

    /**
     * The whole slip box at capture time, so the dialog can render each
     * suggested card's branch path (its address chain) when filing behind.
     */
    private val _lumenBoxCards = MutableStateFlow<List<LumenCardEntity>>(emptyList())
    val lumenBoxCards = _lumenBoxCards.asStateFlow()

    private var pendingLumenContext: PendingLumenContext? = null

    private data class PendingLumenContext(
        val draft: LumenDraft,
        val locatorJson: String?,
        val chapterIndex: Int?,
        val fraction: Float
    )

    /**
     * Captures the passage around the current position and drafts a Lumen
     * card. One small AI call when a key exists; on-device draft otherwise.
     */
    /**
     * Captures a card.
     *
     * [selectionLocatorJson] and [selectedText] arrive when the reader captured
     * from a text selection rather than from the menu. That distinction is the
     * root fix for the duplicate cards: without a selection the passage is a
     * window around the reading position, and two captures a page apart carry
     * mostly the same text, so the model reasonably names the same idea twice.
     * With a selection the reader has pointed at the idea, and the window is
     * centred there instead of on wherever the page happens to sit.
     *
     * A long selection is used as the passage outright. A short one is a
     * pointer, not a passage — a sentence has nothing to build a claim from —
     * so it moves the window rather than replacing it.
     */
    fun captureLumenCard(
        selectionLocatorJson: String? = null,
        selectedText: String? = null,
    ) {
        val b = _book.value ?: return
        // Clear any stuck state from a prior interrupted capture rather than
        // silently bailing out — a cancelled coroutine can leave _lumenCapturing
        // true forever, making every later capture a no-op (spinner spins, no
        // dialog). The draft dialog is the real gate; if it's already open we
        // just don't start another capture.
        if (_lumenDraft.value != null || _lumenCapturing.value) return
        _lumenCapturing.value = true
        viewModelScope.launch {
            try {
                val passage: String
                val chapterIndex: Int?
                // Read once, used by both capture paths. Smaller passages give
                // the model fewer competing ideas to choose between, which is
                // the half of the task it is worst at.
                val captureChars = container.settingsRepository.lumenCaptureChars()
                val selection = selectedText?.trim().orEmpty()
                if (selection.length >= LumenCapture.MIN_SELECTION_PASSAGE_CHARS) {
                    // The reader selected enough to be the passage itself.
                    chapterIndex = if (b.format == "epub") currentChapterIndex() ?: 0 else null
                    passage = selection
                } else if (b.format == "epub") {
                    chapterIndex = currentChapterIndex() ?: 0
                    // The passage ends where the reader pointed and runs back
                    // whole paragraphs from there. Selected text is the exact
                    // anchor when there is any; the locator is the estimate
                    // when there is not.
                    val anchor = selectionLocator(selectionLocatorJson) ?: latestLocator
                    val centered = container.learningContextExtractor.captureEpub(
                        book = b,
                        chapterIndex = chapterIndex,
                        currentLocatorJson = anchor?.toJSON()?.toString(),
                        progressionOverride = anchor?.locations?.progression?.toFloat(),
                        anchorText = selection.takeIf { it.isNotBlank() },
                        targetChars = captureChars,
                    )
                    passage = centered.ifBlank {
                        // No key/href parse failure: reuse the chapter-tail context.
                        container.learningContextExtractor.extract(
                            book = b,
                            chapterIndex = chapterIndex,
                            checkpoint = null,
                            currentLocatorJson = latestLocator?.toJSON()?.toString(),
                            currentTextOffset = null,
                            maxCharacters = 4_000
                        ).recentText.takeLast(captureChars)
                    }
                } else {
                    chapterIndex = null
                    // Same rule as an EPUB: end where the reader is and run
                    // back whole paragraphs. A reformatted transcript has
                    // paragraphs too, and one behaviour for both beats two.
                    passage = LumenCapture.paragraphPassage(
                        _textContent.value.orEmpty(),
                        latestTxtOffset(),
                        captureChars,
                    )
                }
                // Capture diagnostics: if every page yields the same card, this
                // line shows whether the passage itself is frozen (same length /
                // start text) or the AI is at fault.
                val positionInfo =
                    when (b.format) {
                        "epub" -> {
                            val locator = latestLocator
                            "href=${locator?.href} progression=${locator?.locations?.progression} " +
                                "position=${locator?.locations?.position}"
                        }
                        else -> "fraction=$latestTxtFraction pageOffset=$latestTxtPageOffset"
                    }
                Log.d(
                    "LumenCapture",
                    "format=${b.format} chapter=$chapterIndex $positionInfo " +
                        "passageLen=${passage.length} " +
                        "passageStart=${passage.take(80).replace(Regex("\\s+"), " ")}",
                )
                val draft = lumenRepo.draft(b, passage)
                Log.d(
                    "LumenCapture",
                    "usedAi=${draft.usedAi} front=${draft.front.take(60)} quoteLen=${draft.quote.length}",
                )
                pendingLumenContext = PendingLumenContext(
                    draft = draft,
                    locatorJson = if (b.format == "epub") {
                        latestLocator?.toJSON()?.toString()
                    } else null,
                    chapterIndex = chapterIndex,
                    fraction = if (b.format == "txt") latestTxtFraction else 0f
                )
                // Where should this new slip continue the line? Ranked locally
                // against the whole box — Luhmann filed behind the thought it
                // continued, never at random.
                val boxCards = lumenRepo.observeAll().first()
                _lumenBoxCards.value = boxCards
                _lumenFileSuggestions.value = LumenConnections.filingCandidates(
                    cards = boxCards,
                    front = draft.front,
                    back = draft.back,
                    quote = draft.quote,
                    bookId = b.id,
                    box = 1
                )
                _lumenDraft.value = draft
                _captureDiagnostic.value = CaptureDiagnostic.Record.successful(
                    modelState = CaptureDiagnostic.ModelState.generating,
                    captureKind = "LumenCard",
                    usedAi = draft.usedAi,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e("LumenCapture", "Lumen capture failed", error)
                _error.value = "Couldn't create a Lumen card here. Try again."
                _captureDiagnostic.value = CaptureDiagnostic.Record.failed(
                    modelState = CaptureDiagnostic.ModelState.fallbackNoModel,
                    captureKind = "LumenCard",
                    reason = error.message ?: "unknown error",
                )
            } finally {
                _lumenCapturing.value = false
                _captureDiagnostic.value = null
            }
        }
    }

    fun saveLumenCard(front: String, back: String, afterIndex: String? = null) {
        val b = _book.value ?: return
        val pending = pendingLumenContext ?: return
        viewModelScope.launch {
            try {
                lumenRepo.save(
                    book = b,
                    front = front,
                    back = back,
                    quote = pending.draft.quote,
                    sourceLocatorJson = pending.locatorJson,
                    sourceChapterIndex = pending.chapterIndex,
                    sourceFraction = pending.fraction,
                    afterIndex = afterIndex
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _error.value = error.message ?: "Couldn't save the card"
            } finally {
                _lumenDraft.value = null
                _lumenFileSuggestions.value = emptyList()
                _lumenBoxCards.value = emptyList()
                pendingLumenContext = null
            }
        }
    }

    /**
     * Asks the model for another draft of the same passage. The capture window
     * is already held in the pending context, so this re-runs inference only —
     * no re-extraction, and the reader never loses their place.
     */
    /**
     * Re-asks the CLOUD model for this same passage, whatever the reader's
     * configured provider is.
     *
     * The on-device model answers every capture instantly and for nothing, and
     * is honestly limited: it paraphrases a passage rather than stating the
     * idea behind it, and no amount of prompting or decoding changed that. So
     * it drafts everything, and the passages worth more get one tap.
     *
     * Deliberately not automatic. A cloud call costs money and sends the
     * passage off the device, and doing that for every capture would take both
     * decisions away from the reader. One tap keeps the cost where the value
     * is — on the passage they actually cared about.
     */
    fun rewriteLumenCardWithGemini() {
        val b = _book.value ?: return
        val pending = pendingLumenContext ?: return
        if (_lumenCapturing.value) return
        _lumenCapturing.value = true
        viewModelScope.launch {
            try {
                val draft = lumenRepo.draft(
                    b,
                    pending.draft.quote,
                    forceSource = LumenDraftSource.GEMINI,
                )
                pendingLumenContext = pending.copy(draft = draft)
                _lumenDraft.value = draft
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _error.value = error.message ?: "Couldn't rewrite this card"
            } finally {
                _lumenCapturing.value = false
            }
        }
    }

    fun retryLumenCard() {
        val b = _book.value ?: return
        val pending = pendingLumenContext ?: return
        if (_lumenCapturing.value) return
        _lumenCapturing.value = true
        viewModelScope.launch {
            try {
                val draft = lumenRepo.draft(b, pending.draft.quote)
                pendingLumenContext = pending.copy(draft = draft)
                _lumenDraft.value = draft
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _error.value = error.message ?: "Couldn't draft this card again"
            } finally {
                _lumenCapturing.value = false
            }
        }
    }

    /**
     * Explains the reader's selection in the sentence it sits in.
     *
     * The surrounding text comes from the selection itself, so the model is
     * answering about words that are on screen rather than recalling a
     * dictionary entry — which is both the more useful question and the one it
     * is least able to invent.
     */
    fun explainSelection(term: String, before: String, after: String) {
        val b = _book.value ?: return
        askAboutSelection(term.trim(), "Couldn't explain that here.") {
            container.glossRepository
                .explain(
                    term = term,
                    before = before,
                    after = after,
                    bookTitle = b.title,
                    bookId = b.id,
                )
                .map { it.asAnswer() }
        }
    }

    /**
     * Says the reader's selection again in simpler words.
     *
     * The original stays on screen above the rewrite. That is not decoration:
     * the reader most likely to need this is the one least able to judge
     * whether the rewrite is faithful, and putting the two side by side is the
     * only check they can actually make.
     */
    fun simplifySelection(passage: String) {
        val b = _book.value ?: return
        askAboutSelection("In plain English", "Couldn't say that more simply.") {
            container.glossRepository
                .simplify(passage = passage, bookTitle = b.title, bookId = b.id)
                .map { it.asAnswer() }
        }
    }

    /**
     * Runs one selection question into the answer sheet. Only one at a time:
     * the sheet shows a single answer, and a second request would replace the
     * first mid-flight with no way to tell which one arrived.
     */
    private fun askAboutSelection(
        heading: String,
        whenItFails: String,
        request: suspend () -> Result<com.pagetime.app.data.ReaderAnswer>,
    ) {
        if (_answering.value != null) return
        _answerError.value = null
        _answer.value = null
        _answering.value = heading
        viewModelScope.launch {
            try {
                request()
                    .onSuccess { _answer.value = it }
                    .onFailure { error ->
                        _answerError.value = error.message ?: whenItFails
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e("LumenCapture", "Selection question failed", error)
                _answerError.value = error.message ?: whenItFails
            } finally {
                _answering.value = null
            }
        }
    }

    /** The selection's own position, when the capture came from one. */
    private fun selectionLocator(json: String?): org.readium.r2.shared.publication.Locator? =
        json?.let {
            runCatching {
                org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(it))
            }.getOrNull()
        }

    fun dismissAnswer() {
        _answer.value = null
        _answerError.value = null
    }

    fun dismissLumenDraft() {
        _lumenDraft.value = null
        _lumenFileSuggestions.value = emptyList()
        _lumenBoxCards.value = emptyList()
        pendingLumenContext = null
    }

    /** Returns the last on-device capture diagnostic log, newest first. */
    /**
     * The capture log a reader copies, newest activity first, followed by the
     * last capture's verbatim prompt and reply.
     *
     * The rolling lines say a capture happened and how big it was. They cannot
     * say whether the model was given the passage — which is the only question
     * worth asking when a card comes back with nothing to do with the book —
     * so the exchange itself goes on the end.
     */
    fun lastCaptureLog(): List<String> {
        val context = container.lumenRepository.diagContext()
        val rolling = CaptureDiagnostic.recentLog(context)
        val exchange = CaptureDiagnostic.lastExchange(context)
        if (exchange.isEmpty()) return rolling
        return rolling + listOf("", "===== LAST EXCHANGE (verbatim) =====") + exchange
    }

    /** Copies the last capture log to the device clipboard. */
    fun copyCaptureLogToClipboard(context: android.content.Context) {
        val log = lastCaptureLog().joinToString("\n")
        if (log.isBlank()) return
        try {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("PageTime capture log", log))
        } catch (t: Throwable) {
            Log.e("ReaderViewModel", "Failed to copy capture log", t)
        }
    }

    fun setLearningCheckpoint() {
        val b = _book.value ?: return
        persistenceScope.launch {
            when (b.format) {
                "epub" -> {
                    val locator = latestLocator ?: return@launch
                    if (!locatorRestoreComplete) return@launch
                    settingsRepository.saveLearningCheckpoint(
                        LearningCheckpoint(locator.toJSON().toString(), null, null)
                    )
                }
                else -> {
                    if (!txtRestoreComplete) return@launch
                    settingsRepository.saveLearningCheckpoint(
                        LearningCheckpoint(null, latestTxtOffset(), latestTxtFraction)
                    )
                }
            }
            _checkpointPresent.value = true
        }
    }

    private fun latestTxtOffset(): Int {
        val contentLength = _textContent.value?.length ?: 0
        if (contentLength == 0) return 0
        // The exact page-start offset when known, else the fraction estimate.
        return if (latestTxtPageOffset > 0) latestTxtPageOffset
        else (latestTxtFraction.coerceIn(0f, 1f) * contentLength).toInt()
    }

    fun clearLearningCheckpoint() {
        persistenceScope.launch {
            settingsRepository.clearLearningCheckpoint()
            _checkpointPresent.value = false
        }
    }

    fun toggleBookmark() {
        val b = _book.value ?: return
        persistenceScope.launch {
            if (_bookmarkPresent.value) {
                settingsRepository.clearBookmark(b.id)
                _bookmarkPresent.value = false
                return@launch
            }
            when (b.format) {
                "epub" -> {
                    val locator = latestLocator ?: return@launch
                    if (!locatorRestoreComplete) return@launch
                    settingsRepository.saveBookmarkLocator(b.id, locator.toJSON().toString())
                }
                else -> {
                    if (!txtRestoreComplete) return@launch
                    settingsRepository.saveBookmarkScroll(b.id, latestTxtFraction)
                }
            }
            _bookmarkPresent.value = true
        }
    }

    /** Enhance the transcript with AI formatting (speaker labels, paragraphs, etc.). */
    fun enhanceWithAI() {
        val b = _book.value ?: return
        if (b.format != "txt") return
        if (_enhancing.value) return
        val gemini = container.geminiLearningClient
        if (!gemini.isConfigured) {
            _error.value = "Set a Gemini API key in Settings to use AI formatting"
            return
        }
        viewModelScope.launch {
            _enhancing.value = true
            _enhancementProgress.value = null
            try {
                repo.reformatTranscriptWithAI(b.id, gemini) { completed, total ->
                    _enhancementProgress.value = completed to total
                }
                    .onSuccess { formatted ->
                        _textContent.value = formatted
                        _initialTextFraction.value = 0f
                        _initialTextOffset.value = 0
                    }
                    .onFailure { t ->
                        _error.value = t.message ?: "AI formatting failed"
                    }
            } finally {
                _enhancing.value = false
                _enhancementProgress.value = null
            }
        }
    }

    /** Called continuously by the plain-text reader while the user scrolls. */
    fun updateScrollProgress(fraction: Float) {
        if (!ReaderPositionPolicy.canPersist(txtRestoreComplete)) return
        val b = _book.value ?: return
        latestTxtFraction = ReaderPositionPolicy.clampFraction(fraction)
        // A plain-text book is one chapter, so its whole-file fraction IS its
        // position in that chapter — the same coordinate the prompts were
        // stored against.
        onChapterPosition(0, latestTxtFraction)
        txtSaveJob?.cancel()
        txtSaveJob = persistenceScope.launch {
            delay(250)
            repo.updateProgress(b.id, 0, latestTxtFraction)
        }
    }

    /** Flushes the exact current text position before the screen is destroyed. */
    fun persistTextPositionNow(fraction: Float) {
        if (!ReaderPositionPolicy.canPersist(txtRestoreComplete)) return
        val b = _book.value ?: return
        latestTxtFraction = ReaderPositionPolicy.clampFraction(fraction)
        txtSaveJob?.cancel()
        persistenceScope.launch { repo.updateProgress(b.id, 0, latestTxtFraction) }
    }

    /** Marks the Readium initial locator application complete without saving startup state. */
    fun markEpubRestoreComplete() {
        locatorRestoreComplete = true
        latestLocator?.let { locator ->
            val publication = _publication.value
            val index = publication?.readingOrder?.indexOfFirstWithHref(locator.href)
            val fraction = (locator.locations?.progression?.toFloat() ?: 0f).coerceIn(0f, 1f)
            val size = publication?.readingOrder?.size ?: 0
            if (index != null && size > 0) {
                val overall = ((index + fraction) / size).coerceIn(0f, 1f)
                _progress.value = overall
                showResumeNotice(overall)
            }
        }
    }

    private fun showResumeNotice(progress: Float) {
        if (progress <= 0.01f) return
        _resumeNotice.value = "Resumed at ${(progress * 100).toInt()}%"
        viewModelScope.launch {
            delay(4_000)
            _resumeNotice.value = null
        }
    }

    /**
     * Called by the Readium navigator whenever the visible position changes.
     * Persists the exact locator (debounced) so re-opening resumes the precise spot,
     * and derives overall book progress for the HUD.
     */
    fun onLocatorChanged(locator: org.readium.r2.shared.publication.Locator) {
        latestLocator = locator
        if (!locatorRestoreComplete) return

        // Compute whole-book progress from the Readium locator and feed it to the
        // anti-cheat guard. This is what lets the guard distinguish real reading
        // (forward progress) from oscillation/idle for EPUBs — previously only the
        // plain-text path reported progress, so the pace checks never saw EPUBs.
        //
        // The plain-text side then lost it again and read as movement only,
        // which earned nothing at all; see onTextPageChanged. Both paths report
        // progress now, and both must, because minting happens nowhere else.
        val publication = _publication.value
        val index = publication?.readingOrder?.indexOfFirstWithHref(locator.href)
        val fraction = (locator.locations?.progression?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val size = publication?.readingOrder?.size ?: 0
        if (index != null && size > 0) {
            onProgressChanged((index + fraction) / size)
        } else {
            onUserScrolled()
        }

        if (index != null) onChapterPosition(index, fraction)

        // Debounce: coalesce bursts of locator updates into one write.
        locatorSaveJob?.cancel()
        locatorSaveJob = persistenceScope.launch {
            delay(500)
            saveLocator()
        }
    }

    private suspend fun saveLocator() {
        val b = _book.value ?: return
        val locator = latestLocator ?: return
        val locatorJson = locator.toJSON().toString()
        settingsRepository.saveLocator(b.id, locatorJson)

        // Keep the in-memory restore locator current. The Readium navigator is
        // recreated from this whenever the reader screen leaves and re-enters
        // composition (e.g. viewing Lumen cards then coming back), so a stale
        // session-open value here is what made the book appear to "jump back"
        // a few pages. Syncing it on every save makes re-entry resume the exact
        // spot the reader was actually at.
        _initialLocatorJson.value = locatorJson

        // Keep the legacy DB progress roughly in sync (library UI shows it).
        val publication = _publication.value ?: return
        val index = publication.readingOrder.indexOfFirstWithHref(locator.href) ?: 0
        val frac = (locator.locations?.progression?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val size = publication.readingOrder.size
        val overall = if (size > 0) ((index + frac) / size).toFloat().coerceIn(0f, 1f) else 0f
        repo.updateProgress(b.id, index, overall)
    }

    /** Persists the most recent position immediately (exit, background, checkpoint). */
    private fun persistPositionNow() {
        val b = _book.value ?: return
        when (b.format) {
            "epub" -> {
                if (!ReaderPositionPolicy.canPersist(locatorRestoreComplete)) return
                if (latestLocator == null) return
                locatorSaveJob?.cancel()
                persistenceScope.launch { saveLocator() }
            }
            else -> persistTextPositionNow(latestTxtFraction)
        }
    }


    // ---- Searching the book by meaning -------------------------------------

    private val bookIndexer = container.bookIndexer
    private val bookSearcher = container.bookSearcher

    private val _bookSearch = MutableStateFlow(BookSearchState())
    val bookSearch = _bookSearch.asStateFlow()

    private var indexJob: Job? = null
    private var searchJob: Job? = null

    /**
     * How much of this book is already searchable.
     *
     * Called when the sheet opens rather than on load: it parses the book to
     * count chapters, which is not work to do for every reader who never opens
     * search.
     */
    fun refreshSearchState() {
        val b = _book.value ?: return
        viewModelScope.launch {
            val installed = bookIndexer.modelId() != null
            val progress = runCatching { bookIndexer.progressFor(b) }.getOrNull()
            _bookSearch.value = _bookSearch.value.copy(
                modelInstalled = installed,
                chaptersTotal = progress?.chaptersTotal ?: 0,
                chaptersIndexed = progress?.chaptersDone ?: 0,
            )
        }
    }

    /**
     * Reads the whole book once, turning it into vectors.
     *
     * Runs in viewModelScope deliberately: leaving the book stops it. Indexing
     * resumes from the last finished chapter, so a stopped index costs one
     * chapter rather than the book, and holding a phone open on a screen for
     * ten minutes is not a thing to demand of anyone.
     */
    fun startIndexing() {
        val b = _book.value ?: return
        if (indexJob?.isActive == true) return
        _bookSearch.value = _bookSearch.value.copy(indexing = true)
        indexJob = viewModelScope.launch {
            try {
                val done = bookIndexer.index(b) { progress ->
                    _bookSearch.value = _bookSearch.value.copy(
                        chaptersIndexed = progress.chaptersDone,
                        chaptersTotal = progress.chaptersTotal,
                    )
                }
                _bookSearch.value = _bookSearch.value.copy(
                    chaptersIndexed = done.chaptersDone,
                    chaptersTotal = done.chaptersTotal,
                )
            } finally {
                _bookSearch.value = _bookSearch.value.copy(indexing = false)
            }
        }
    }

    fun stopIndexing() {
        indexJob?.cancel()
        indexJob = null
        _bookSearch.value = _bookSearch.value.copy(indexing = false)
    }

    /** Throws the index away; it is derived data and can always be rebuilt. */
    fun deleteIndex() {
        val b = _book.value ?: return
        // Waits for the index to actually stop before deleting. Cancellation is
        // a request, not an event: a chapter mid-flight could otherwise write
        // its rows after the delete and leave a fragment of an index the reader
        // believes they threw away.
        val running = indexJob
        indexJob = null
        viewModelScope.launch {
            runCatching { running?.cancelAndJoin() }
            _bookSearch.value = _bookSearch.value.copy(indexing = false)
            runCatching { bookIndexer.delete(b) }
            _bookSearch.value = _bookSearch.value.copy(
                chaptersIndexed = 0,
                results = emptyList(),
                answered = false,
            )
        }
    }

    fun onSearchQueryChanged(text: String) {
        _bookSearch.value = _bookSearch.value.copy(query = text)
    }

    fun searchBook() {
        val b = _book.value ?: return
        val query = _bookSearch.value.query.trim()
        searchJob?.cancel()
        if (query.isBlank()) {
            _bookSearch.value = _bookSearch.value.copy(results = emptyList(), answered = false)
            return
        }
        _bookSearch.value = _bookSearch.value.copy(searching = true)
        searchJob = viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) {
                runCatching { bookSearcher.search(b, query) }.getOrDefault(emptyList())
            }
            _bookSearch.value = _bookSearch.value.copy(
                searching = false,
                results = hits,
                answered = true,
            )
        }
    }


    // ---- Chapter prompts -----------------------------------------------------

    private val chapterPrompts = container.chapterPromptGenerator

    private val _promptState = MutableStateFlow(ChapterPromptState())
    val promptState = _promptState.asStateFlow()

    private var promptJob: Job? = null

    /** Ids judged this sitting, so a dismissed prompt cannot flicker back. */
    private val judgedPrompts = mutableSetOf<String>()

    /**
     * Where the reader is inside the current chapter, and which prompt that
     * makes due.
     *
     * Fed from both reading paths — the Readium locator for EPUBs and the
     * scroll fraction for plain text — because a prompt has to know where the
     * reader is regardless of which engine is showing the book.
     */
    private fun onChapterPosition(chapterIndex: Int, fraction: Float) {
        val state = _promptState.value
        if (state.chapterIndex != chapterIndex) {
            // A new chapter: whatever was pending belongs to the old one.
            _promptState.value = ChapterPromptState(chapterIndex = chapterIndex)
            loadPendingPrompts(chapterIndex)
            return
        }
        if (state.pending.isEmpty()) return
        val surfaced = PromptSurfacing.next(state.surfaceable, fraction, judgedPrompts)
        _promptState.value = state.copy(
            progression = fraction,
            surfacedId = surfaced?.id,
            stillAhead = PromptSurfacing.remaining(state.surfaceable, fraction, judgedPrompts),
        )
    }

    private fun loadPendingPrompts(chapterIndex: Int) {
        val b = _book.value ?: return
        viewModelScope.launch {
            val ready = runCatching { chapterPrompts.isReady(b, chapterIndex) }.getOrDefault(false)
            val pending = chapterPrompts.pending(b, chapterIndex)
            _promptState.value = _promptState.value.copy(
                chapterIndex = chapterIndex,
                ready = ready,
                pending = pending,
            )
        }
    }

    /**
     * Writes this chapter's questions.
     *
     * Deliberately a deliberate act. Generating costs an API call, and doing it
     * automatically on every chapter of a forty-chapter book would spend the
     * reader's quota on chapters they may never reach and questions they may
     * never want.
     */
    fun generateChapterPrompts() = runGeneration(fresh = false)

    /**
     * Asks for a different set of questions.
     *
     * Never automatic. A change to the instructions could otherwise invalidate
     * every chapter of every book at once and spend the reader's quota
     * re-answering questions they were perfectly happy with.
     */
    fun regenerateChapterPrompts() = runGeneration(fresh = true)

    /**
     * Loads what became of each passage the last generation sent.
     *
     * Read on demand rather than kept in the reading state: it is a diagnostic
     * the reader opens occasionally, and holding two dozen paragraphs of text
     * in memory for every chapter they walk past is not worth it.
     */
    fun loadChapterCoverage() {
        val b = _book.value ?: return
        val chapterIndex = _promptState.value.chapterIndex
        viewModelScope.launch {
            val rows = chapterPrompts.passages(b, chapterIndex)
            _promptState.value = _promptState.value.copy(coverage = rows)
        }
    }

    /**
     * The reader disagreed: write a question for these passages.
     *
     * Additive. Nothing already made is thrown away — the reader is asking for
     * more from paragraphs that produced none, not for a different set.
     */
    fun makeCardsForPassages(ordinals: Set<Int>) {
        val b = _book.value ?: return
        if (promptJob?.isActive == true || ordinals.isEmpty()) return
        val chapterIndex = _promptState.value.chapterIndex
        _promptState.value = _promptState.value.copy(generating = true, stage = null, result = null)
        promptJob = viewModelScope.launch {
            try {
                val onStage: (ChapterPromptGenerator.Stage) -> Unit = { stage ->
                    _promptState.value = _promptState.value.copy(stage = stage)
                }
                val result = chapterPrompts.generateForPassages(
                    b, chapterIndex, null, ordinals, onStage,
                )
                // The chapter's pending set is re-read rather than replaced:
                // this run only covers the chosen passages, and overwriting
                // would drop every question the earlier run had made.
                val pending = chapterPrompts.pending(b, chapterIndex)
                val state = _promptState.value
                _promptState.value = state.copy(
                    pending = pending,
                    result = result,
                    coverage = chapterPrompts.passages(b, chapterIndex),
                    surfacedId = PromptSurfacing.next(
                        pending.map {
                            com.pagetime.app.data.learning.SurfaceablePrompt(it.id, it.sourceFraction ?: 0f)
                        },
                        state.progression,
                        judgedPrompts,
                    )?.id,
                    stillAhead = PromptSurfacing.remaining(
                        pending.map {
                            com.pagetime.app.data.learning.SurfaceablePrompt(it.id, it.sourceFraction ?: 0f)
                        },
                        state.progression,
                        judgedPrompts,
                    ),
                )
            } finally {
                _promptState.value = _promptState.value.copy(generating = false, stage = null)
            }
        }
    }

    private fun runGeneration(fresh: Boolean) {
        val b = _book.value ?: return
        if (promptJob?.isActive == true) return
        val chapterIndex = _promptState.value.chapterIndex
        judgedPrompts.clear()
        _promptState.value = _promptState.value.copy(generating = true, stage = null, result = null)
        promptJob = viewModelScope.launch {
            try {
                val onStage: (ChapterPromptGenerator.Stage) -> Unit = { stage ->
                    _promptState.value = _promptState.value.copy(stage = stage)
                }
                val result = if (fresh) {
                    chapterPrompts.regenerate(b, chapterIndex, null, onStage)
                } else {
                    chapterPrompts.generate(b, chapterIndex, null, onStage = onStage)
                }
                val state = _promptState.value
                _promptState.value = state.copy(
                    pending = result.cards,
                    result = result,
                    coverage = chapterPrompts.passages(b, chapterIndex),
                    // Anything already behind the reader can show at once
                    // rather than waiting for the next page turn.
                    surfacedId = PromptSurfacing.next(
                        result.cards.map {
                            com.pagetime.app.data.learning.SurfaceablePrompt(it.id, it.sourceFraction ?: 0f)
                        },
                        state.progression,
                        judgedPrompts,
                    )?.id,
                    stillAhead = PromptSurfacing.remaining(
                        result.cards.map {
                            com.pagetime.app.data.learning.SurfaceablePrompt(it.id, it.sourceFraction ?: 0f)
                        },
                        state.progression,
                        judgedPrompts,
                    ),
                )
            } finally {
                _promptState.value = _promptState.value.copy(generating = false, stage = null)
            }
        }
    }

    /** Dismisses the status line without touching the questions themselves. */
    fun clearPromptMessage() {
        _promptState.value = _promptState.value.copy(result = null)
    }

    /**
     * Grading for questions answered in the reading chair.
     *
     * The same object the review sitting uses, so a card rated Good here and a
     * card rated Good there get the same interval from the same scheduler.
     */
    private val chapterCardGrader by lazy {
        com.pagetime.app.data.review.ChapterCardGrader(
            container.database.learningCardDao(),
            container.database.learningReviewLogDao(),
        )
    }

    /**
     * The reader answers a question while still in the chapter.
     *
     * This is a REVIEW, not a purchase. It used to be "Keep it", which said
     * nothing about whether the reader knew the answer, so the card entered
     * the deck with no history and fell due immediately — and the first thing
     * a review sitting did was ask a question answered ten minutes earlier.
     * The most valuable retrieval of all, the one taken while the passage is
     * still warm, was being thrown away.
     *
     * Now the grade goes to FSRS, the scheduler picks the next date, and
     * answering is itself how the card is accepted.
     */
    fun gradePrompt(cardId: String, rating: com.pagetime.app.data.LumenRating) {
        val honest = com.pagetime.app.data.review.FirstReview.inTheChair(rating.value)
        val applied = com.pagetime.app.data.LumenRating.entries.firstOrNull { it.value == honest } ?: rating
        judgePrompt(cardId, kept = true)
        viewModelScope.launch {
            runCatching {
                chapterCardGrader.grade(cardId, applied, java.time.Instant.now(), keepIfUnjudged = true)
            }
            // The reader now owns a card with a date on it, and this is the
            // moment to ask whether the app may say so when that date comes.
            // Quantum Country asks at exactly this point and for exactly this
            // reason: at a cold first launch the request is noise about a
            // feature you have no cards for, and here it is obviously about
            // the thing you just did.
            runCatching {
                if (!settingsRepository.remindersPermissionAsked()) {
                    _askReminderPermission.value = true
                }
            }
        }
    }

    /**
     * Whether to put the notification permission dialog up.
     *
     * Held here rather than in the screen because the condition is a stored
     * fact — have we ever asked — and a screen that is recomposed or rotated
     * must not ask again.
     */
    private val _askReminderPermission = MutableStateFlow(false)
    val askReminderPermission: StateFlow<Boolean> = _askReminderPermission.asStateFlow()

    /**
     * Records what came back from the system dialog.
     *
     * A refusal switches the preference off rather than leaving it on and
     * silent. An app that believes it is reminding you while Android drops
     * every notification is worse than one that admits it is off, because the
     * reader has no way to tell the difference until the cards have gone
     * stale.
     */
    fun reminderPermissionAnswered(granted: Boolean) {
        _askReminderPermission.value = false
        persistenceScope.launch {
            runCatching {
                settingsRepository.setRemindersPermissionAsked(true)
                settingsRepository.setReviewReminders(granted)
                // Scheduled here and not only at launch. The permission was
                // granted seconds ago and the first card is already due in
                // days; waiting for the next cold start to arm the worker
                // would be an invisible way to miss the first reminder.
                if (granted) {
                    com.pagetime.app.data.review.ReviewReminderWorker.schedule(app)
                } else {
                    com.pagetime.app.data.review.ReviewReminderWorker.cancel(app)
                }
            }
        }
    }

    fun skipPrompt(cardId: String) {
        judgePrompt(cardId, kept = false)
        viewModelScope.launch { runCatching { chapterPrompts.skip(cardId) } }
    }

    /**
     * Takes the prompt off screen straight away.
     *
     * The row is updated a moment later on the database's own time; waiting for
     * it before moving on would make every answer feel laggy, and the judged
     * set is what stops the card flickering back in the meantime.
     */
    private fun judgePrompt(cardId: String, kept: Boolean) {
        judgedPrompts += cardId
        val state = _promptState.value
        _promptState.value = state.copy(
            surfacedId = PromptSurfacing.next(state.surfaceable, state.progression, judgedPrompts)?.id,
            stillAhead = PromptSurfacing.remaining(state.surfaceable, state.progression, judgedPrompts),
            keptCount = state.keptCount + if (kept) 1 else 0,
        )
    }

    fun applyReaderSettings(settings: ReaderSettings) = viewModelScope.launch {
        settingsRepository.setReaderSettings(settings)
    }

    /** Persists the brightness chosen with the Kobo-style edge gesture. */
    fun setReaderBrightness(value: Float?) {
        persistenceScope.launch {
            settingsRepository.setReaderBrightness(value)
        }
    }

    override fun onCleared() {
        // stopReading() also saves the reading position and flushes pending seconds.
        stopReading()
        locatorSaveJob?.cancel()
        txtSaveJob?.cancel()
        indexJob?.cancel()
        searchJob?.cancel()
        promptJob?.cancel()
        super.onCleared()
    }
}

class ReaderViewModelFactory(
    private val app: Application,
    private val bookId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderViewModel(app, bookId) as T
}

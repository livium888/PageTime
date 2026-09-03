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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        loadBook()
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
        if (_book.value == null) return
        if (tickerJob?.isActive == true) return
        guard.start(SystemClock.elapsedRealtime())
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
        pageStartOffset: Int = 0
    ) {
        if (pageCount <= 0) return
        latestTxtPageOffset = pageStartOffset
        val fraction = TextPageLayout.fractionForPage(pageIndex, pageCount)
        _progress.value = fraction
        if (!userInitiated || !txtRestoreComplete) return
        onUserScrolled()
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
                val selection = selectedText?.trim().orEmpty()
                if (selection.length >= LumenCapture.MIN_SELECTION_PASSAGE_CHARS) {
                    // The reader selected enough to be the passage itself.
                    chapterIndex = if (b.format == "epub") currentChapterIndex() ?: 0 else null
                    passage = selection
                } else if (b.format == "epub") {
                    chapterIndex = currentChapterIndex() ?: 0
                    // Centered on the selection when there is one, otherwise on
                    // the current locator so the passage follows the page the
                    // user is actually reading (two pages → two passages).
                    // Falls back to the chapter tail if the window cannot be read.
                    val anchor = selectionLocator(selectionLocatorJson) ?: latestLocator
                    val centered = container.learningContextExtractor.captureEpub(
                        book = b,
                        chapterIndex = chapterIndex,
                        currentLocatorJson = anchor?.toJSON()?.toString(),
                        progressionOverride = anchor?.locations?.progression?.toFloat()
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
                        ).recentText.takeLast(1_500)
                    }
                } else {
                    chapterIndex = null
                    passage = LumenCapture.captureWindow(
                        _textContent.value.orEmpty(),
                        latestTxtOffset()
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
    fun lastCaptureLog(): List<String> = CaptureDiagnostic.recentLog(container.lumenRepository.diagContext())

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
        val publication = _publication.value
        val index = publication?.readingOrder?.indexOfFirstWithHref(locator.href)
        val fraction = (locator.locations?.progression?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val size = publication?.readingOrder?.size ?: 0
        if (index != null && size > 0) {
            onProgressChanged((index + fraction) / size)
        } else {
            onUserScrolled()
        }

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

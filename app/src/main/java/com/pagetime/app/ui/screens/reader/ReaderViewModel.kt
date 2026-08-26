package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ReaderSettings
import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.learning.AiGenerationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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

    val readerSettings = settingsRepository.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    /** Concepts already discovered for this book; used only by the EPUB hint layer. */
    val conceptMap = container.conceptMapRepository.observeBookMap(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConceptMap(emptyList(), emptyList()))

    val aiSettings = settingsRepository.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.pagetime.app.data.local.AiSettings())

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

    private val _aiGenerationState = MutableStateFlow<AiGenerationState>(AiGenerationState.Idle)
    val aiGenerationState = _aiGenerationState.asStateFlow()

    private val _mapMoment = MutableStateFlow<com.pagetime.app.data.local.MapMoment?>(null)
    val mapMoment = _mapMoment.asStateFlow()

    private var readingSecondsSinceCheckpoint = 0L
    private var lastCheckpointProgress = -1f

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
        readingSecondsSinceCheckpoint = 0L
        lastCheckpointProgress = -1f
        _aiGenerationState.value = AiGenerationState.Idle
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
                    readingSecondsSinceCheckpoint++
                    maybeGenerateCardsAtReadingCheckpoint()
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

    /** Allows the reader to recover immediately from a failed or missed automatic run. */
    fun generateCardsNow() {
        val b = _book.value ?: return
        val chapterIndex = currentChapterIndex() ?: b.currentChapterIndex
        if (b.format != "epub" && chapterIndex < 0) return
        generateCardsForChapter(
            chapterIndex = chapterIndex,
            locatorJson = latestLocator?.toJSON()?.toString(),
            textFraction = latestTxtFraction.takeIf { b.format == "txt" },
            force = true
        )
    }

    /** Starts a small comprehension checkpoint after roughly three minutes of active reading. */
    private fun maybeGenerateCardsAtReadingCheckpoint() {
        val book = _book.value ?: return
        val progress = _progress.value.coerceIn(0f, 1f)
        val generating = _aiGenerationState.value is AiGenerationState.Generating
        if (!ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = readingSecondsSinceCheckpoint,
                progress = progress,
                lastCheckpointProgress = lastCheckpointProgress,
                generationInProgress = generating,
                intervalSeconds = aiSettings.value.analysisLevel.intervalSeconds
            )
        ) return

        val chapterIndex = if (book.format == "epub") {
            currentChapterIndex() ?: return
        } else {
            (progress.coerceIn(0f, 0.9999f) * 5f).toInt()
        }
        readingSecondsSinceCheckpoint = 0L
        lastCheckpointProgress = progress
        generateCardsForChapter(
            chapterIndex = chapterIndex,
            locatorJson = latestLocator?.toJSON()?.toString(),
            textFraction = progress,
            readingProgress = progress
        )
    }

    private fun currentChapterIndex(): Int? {
        val locator = latestLocator ?: return null
        val publication = _publication.value ?: return null
        return publication.readingOrder.indexOfFirstWithHref(locator.href)
    }

    /** Starts one bounded Gemini generation request without blocking the reader. */
    private fun generateCardsForChapter(
        chapterIndex: Int,
        locatorJson: String?,
        textFraction: Float?,
        readingProgress: Float? = null,
        force: Boolean = false
    ) {
        val b = _book.value ?: return
        if (b.format != "epub" && chapterIndex < 0) return
        if (_aiGenerationState.value is AiGenerationState.Generating) return
        _aiGenerationState.value = AiGenerationState.Generating
        persistenceScope.launch {
            try {
                val result = container.learningRepository.generateCardsForChapter(
                    bookId = b.id,
                    chapterIndex = chapterIndex,
                    locatorJson = locatorJson,
                    textFraction = textFraction,
                    readingProgress = readingProgress,
                    force = force
                )
                _aiGenerationState.value = AiGenerationState.Generated(
                    count = result.cards.size,
                    topicCount = result.cards.map { it.topic }.distinctBy(String::lowercase).size
                )

                // A map outage must not turn successfully saved cards into a failure.
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
                delay(4_000)
                if (_aiGenerationState.value is AiGenerationState.Generated) {
                    _aiGenerationState.value = AiGenerationState.Idle
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // A transient failure must not pin an alarming notice to the screen
                // forever: show the real reason briefly, then clear it so the next
                // chapter trigger or a manual "Generate cards now" can retry.
                _aiGenerationState.value = AiGenerationState.Failed(
                    error.message ?: "Could not create a review card"
                )
                delay(8_000)
                if (_aiGenerationState.value is AiGenerationState.Failed) {
                    _aiGenerationState.value = AiGenerationState.Idle
                }
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
        val fraction = TextPageLayout.fractionForPage(pageIndex, pageCount)
        _progress.value = fraction
        if (!userInitiated || !txtRestoreComplete) return
        onUserScrolled()
        updateScrollProgress(fraction)
        persistenceScope.launch {
            settingsRepository.saveTextOffset(bookId, pageStartOffset)
        }
    }

    fun createLearningCard(
        prompt: String,
        answer: String,
        explanation: String?,
        chapterTitle: String?
    ) = persistenceScope.launch {
        val b = _book.value ?: return@launch
        val publication = _publication.value
        val locator = latestLocator
        val chapterIndex = if (publication != null && locator != null) {
            publication.readingOrder.indexOfFirstWithHref(locator.href) ?: 0
        } else {
            b.currentChapterIndex
        }
        container.learningRepository.createCard(
            bookId = b.id,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            prompt = prompt,
            answer = answer,
            explanation = explanation,
            sourceLocator = locator?.toJSON()?.toString(),
            sourceFraction = latestTxtFraction.takeIf { b.format == "txt" }
        )
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
        settingsRepository.saveLocator(b.id, locator.toJSON().toString())

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

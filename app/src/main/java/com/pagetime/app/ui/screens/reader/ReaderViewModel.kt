package com.pagetime.app.ui.screens.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ReaderSettings
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

    val readerSettings = settingsRepository.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    val balanceSeconds = balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var tickerJob: Job? = null
    private var pendingSeconds = 0L
    private var resumed = false

    /** Latest position reported by the navigator (EPUB only). */
    @Volatile
    private var latestLocator: org.readium.r2.shared.publication.Locator? = null

    private var locatorSaveJob: Job? = null

    init {
        loadBook()
    }

    fun retry() {
        _error.value = null
        _textContent.value = null
        _publication.value = null
        _initialLocatorJson.value = null
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val loaded = if (bookId == "last") repo.getMostRecentBook() else repo.getBook(bookId)
            _book.value = loaded
            if (loaded == null) {
                _error.value = "Book not found in library"
                return@launch
            }
            // The lifecycle may have already reached ON_RESUME before the book
            // finished loading; make sure the timer actually starts.
            if (resumed) tryStartTicker()

            if (loaded.format == "epub") {
                withContext(Dispatchers.IO) {
                    openEpub(loaded)
                }
            } else {
                withContext(Dispatchers.IO) {
                    _textContent.value = runCatching { File(loaded.localPath).readText() }.getOrNull()
                        ?: run {
                            _error.value = "Cannot read this book's file."
                            null
                        }
                }
                // Seed from the DB so an early checkpoint can't overwrite the saved
                // position with 0 before the user has scrolled anywhere.
                latestTxtFraction = loaded.scrollProgress.coerceIn(0f, 1f)
            }
        }
    }

    private suspend fun openEpub(book: BookEntity) {
        // Read the saved position BEFORE publishing the publication: the navigator
        // host is created the moment `_publication` flips non-null and must have the
        // locator at that instant to restore the exact reading spot.
        val savedLocatorJson = settingsRepository.savedLocator(book.id)
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
        _initialLocatorJson.value = savedLocatorJson
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
        guard.start(System.currentTimeMillis())
        _guardState.value = guard.state
        tickerJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                ticks++
                val now = System.currentTimeMillis()
                guard.onTick(now)
                _sessionSeconds.value++
                if (guard.state.crediting) {
                    pendingSeconds++
                    _creditedSeconds.value++
                }
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
        guard.onMovement(System.currentTimeMillis())
        _guardState.value = guard.state
    }

    fun onProgressChanged(progress: Float) {
        _progress.value = progress.coerceIn(0f, 1f)
        guard.onProgress(_progress.value, System.currentTimeMillis())
        _guardState.value = guard.state
    }

    fun resumeAfterIdle() {
        guard.onContinueTapped(System.currentTimeMillis())
        _guardState.value = guard.state
    }

    /** Plain-text reader's current scroll fraction (0..1 of the whole book). */
    @Volatile
    private var latestTxtFraction: Float = 0f

    /** Called continuously by the plain-text reader while the user scrolls. */
    fun updateScrollProgress(fraction: Float) {
        val b = _book.value ?: return
        latestTxtFraction = fraction.coerceIn(0f, 1f)
        persistenceScope.launch { repo.updateProgress(b.id, 0, latestTxtFraction) }
    }

    /**
     * Called by the Readium navigator whenever the visible position changes.
     * Persists the exact locator (debounced) so re-opening resumes the precise spot,
     * and derives overall book progress for the HUD.
     */
    fun onLocatorChanged(locator: org.readium.r2.shared.publication.Locator) {
        latestLocator = locator
        onUserScrolled()

        val publication = _publication.value ?: return
        val index = publication.readingOrder.indexOfFirstWithHref(locator.href)
        val fraction = (locator.locations?.progression?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val size = publication.readingOrder.size
        if (index != null && size > 0) {
            _progress.value = ((index + fraction) / size).coerceIn(0f, 1f)
        }

        // Debounce: coalesce bursts of locator updates into one write.
        if (locatorSaveJob?.isActive != true) {
            locatorSaveJob = persistenceScope.launch {
                delay(500)
                saveLocator()
            }
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
                val locator = latestLocator ?: return
                persistenceScope.launch { saveLocator() }
            }
            else -> {
                val fraction = latestTxtFraction
                persistenceScope.launch { repo.updateProgress(b.id, 0, fraction) }
            }
        }
    }

    fun setFontSize(v: Float) = viewModelScope.launch { settingsRepository.setFontSize(v) }
    fun setLineHeight(v: Float) = viewModelScope.launch { settingsRepository.setLineHeight(v) }
    fun setFontFamily(v: String) = viewModelScope.launch { settingsRepository.setFontFamily(v) }
    fun setTheme(v: String) = viewModelScope.launch { settingsRepository.setTheme(v) }
    fun setMargin(v: Float) = viewModelScope.launch { settingsRepository.setMargin(v) }

    override fun onCleared() {
        // stopReading() also saves the reading position and flushes pending seconds.
        stopReading()
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

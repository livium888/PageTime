package com.pagetime.app.ui.screens.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.library.EpubChapter
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
import java.io.File

class ReaderViewModel(private val app: Application, private val bookId: String) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository
    private val balanceManager = container.balanceManager
    private val settingsRepository = container.settingsRepository

    private val guard = ReadingGuard()

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book = _book.asStateFlow()

    private val _chapters = MutableStateFlow<List<EpubChapter>>(emptyList())
    val chapters = _chapters.asStateFlow()

    private val _extractRoot = MutableStateFlow<String?>(null)
    val extractRoot = _extractRoot.asStateFlow()

    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex = _chapterIndex.asStateFlow()

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

    val readerSettings = settingsRepository.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    val balanceSeconds = balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var tickerJob: Job? = null
    private var pendingSeconds = 0L
    private var resumed = false

    init {
        loadBook()
    }

    fun retry() {
        _error.value = null
        _chapters.value = emptyList()
        _extractRoot.value = null
        _textContent.value = null
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
                    runCatching {
                        container.epubParser.parse(
                            File(loaded.localPath),
                            File(app.cacheDir, "epub/${loaded.id}")
                        )
                    }.onSuccess { epub ->
                        _chapters.value = epub.chapters
                        _extractRoot.value = File(app.cacheDir, "epub/${loaded.id}").absolutePath
                        _chapterIndex.value = loaded.currentChapterIndex
                            .coerceIn(0, (epub.chapters.size - 1).coerceAtLeast(0))
                    }.onFailure { t ->
                        _error.value = "Cannot open this EPUB: ${t.message}"
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    _textContent.value = runCatching { File(loaded.localPath).readText() }.getOrNull()
                }
            }
        }
    }

    fun startReading() {
        resumed = true
        tryStartTicker()
    }

    fun stopReading() {
        resumed = false
        tickerJob?.cancel()
        tickerJob = null
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
                if (ticks % 5 == 0) flush()
            }
        }
    }

    private fun flush() {
        if (pendingSeconds <= 0) return
        val seconds = pendingSeconds
        pendingSeconds = 0
        val currentBook = _book.value ?: return
        viewModelScope.launch {
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

    fun goToChapter(index: Int) {
        val size = _chapters.value.size
        if (size == 0) return
        val target = index.coerceIn(0, size - 1)
        if (target == _chapterIndex.value) return
        _chapterIndex.value = target
        _book.value?.let { b ->
            viewModelScope.launch { repo.updateProgress(b.id, target, 0f) }
        }
        onProgressChanged(if (size > 1) target.toFloat() / (size - 1) else 0f)
    }

    fun updateScrollProgress(progress: Float) {
        _book.value?.let { b ->
            viewModelScope.launch { repo.updateProgress(b.id, _chapterIndex.value, progress) }
        }
    }

    fun setFontSize(v: Float) = viewModelScope.launch { settingsRepository.setFontSize(v) }
    fun setLineHeight(v: Float) = viewModelScope.launch { settingsRepository.setLineHeight(v) }
    fun setFontFamily(v: String) = viewModelScope.launch { settingsRepository.setFontFamily(v) }
    fun setTheme(v: String) = viewModelScope.launch { settingsRepository.setTheme(v) }
    fun setMargin(v: Float) = viewModelScope.launch { settingsRepository.setMargin(v) }

    override fun onCleared() {
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

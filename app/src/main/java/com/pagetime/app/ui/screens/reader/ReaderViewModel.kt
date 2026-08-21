package com.pagetime.app.ui.screens.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.library.EpubChapter
import com.pagetime.app.data.local.BookEntity
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

class ReaderViewModel(app: Application, private val bookId: String) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository
    private val balanceManager = container.balanceManager

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

    private val _sessionSeconds = MutableStateFlow(0L)
    val sessionSeconds = _sessionSeconds.asStateFlow()

    val balanceSeconds = balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var tickerJob: Job? = null
    private var pendingSeconds = 0L

    init {
        viewModelScope.launch {
            val loaded = if (bookId == "last") repo.getMostRecentBook() else repo.getBook(bookId)
            _book.value = loaded
            if (loaded == null) return@launch

            if (loaded.format == "epub") {
                withContext(Dispatchers.IO) {
                    val epub = container.epubParser.parse(
                        File(loaded.localPath),
                        File(app.cacheDir, "epub/${loaded.id}")
                    )
                    _chapters.value = epub.chapters
                    _extractRoot.value = File(app.cacheDir, "epub/${loaded.id}").absolutePath
                    _chapterIndex.value = loaded.currentChapterIndex
                        .coerceIn(0, (epub.chapters.size - 1).coerceAtLeast(0))
                }
            } else {
                withContext(Dispatchers.IO) {
                    _textContent.value = runCatching { File(loaded.localPath).readText() }.getOrNull()
                }
            }
        }
    }

    fun startReading() {
        if (_book.value == null) return
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                ticks++
                pendingSeconds++
                _sessionSeconds.value = pendingSeconds
                if (ticks % 5 == 0) flush()
            }
        }
    }

    fun stopReading() {
        tickerJob?.cancel()
        tickerJob = null
        flush()
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

    fun goToChapter(index: Int) {
        val size = _chapters.value.size
        if (size == 0) return
        val target = index.coerceIn(0, size - 1)
        if (target == _chapterIndex.value) return
        _chapterIndex.value = target
        _book.value?.let { b ->
            viewModelScope.launch { repo.updateProgress(b.id, target, 0f) }
        }
    }

    fun updateScrollProgress(progress: Float) {
        _book.value?.let { b ->
            viewModelScope.launch { repo.updateProgress(b.id, _chapterIndex.value, progress) }
        }
    }

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

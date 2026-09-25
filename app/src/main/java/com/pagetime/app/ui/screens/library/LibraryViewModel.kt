package com.pagetime.app.ui.screens.library

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.BookGenreSummary
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.domain.ReadingStreak
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    val books = container.libraryRepository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val balanceSeconds = container.balanceManager.sessionSecondsRemainingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastMapMoment = container.settingsRepository.lastMapMoment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalReadingSeconds = container.settingsRepository.settings
        .map { it.totalReadingSeconds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * The book to put in front of the reader: the one most recently opened,
     * even if finished — there is nowhere better to point "continue" at, and
     * re-opening a finished book to check something or start a re-read is a
     * legitimate reason to land there too. Falls back to the newest import
     * when nothing has been opened yet (mirrors
     * [com.pagetime.app.data.LibraryRepository.getMostRecentBook], reactively).
     */
    val upNextBook = combine(
        books,
        container.settingsRepository.observeLastReadBookId
    ) { books, lastId ->
        books.firstOrNull { it.id == lastId } ?: books.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val activeReadingDays = container.usageRepository.activeReadingDays()
        .map { days -> days.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** "Don't break the chain": consecutive local calendar days with reading or flashcard credit. */
    val readingStreak = activeReadingDays
        .map { days -> ReadingStreak.currentStreak(days, LocalDate.now().toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val didReadToday = activeReadingDays
        .map { days -> ReadingStreak.didToday(days, LocalDate.now().toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** One-time "never miss twice" nudge: yesterday was missed, today hasn't happened yet. */
    val showNeverMissTwiceNudge = activeReadingDays
        .map { days -> ReadingStreak.needsNeverMissTwiceNudge(days, LocalDate.now().toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "2 Fiction, 1 Poetry" — null until at least one book has a genre. */
    val genreSummary = books
        .map { BookGenreSummary.label(BookGenreSummary.summarize(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Today's one dismissible book suggestion, or null when there isn't one —
     * dismissed for today, from a previous day and not yet refreshed, or
     * pointing at a book that's since been deleted. [LibrarianSuggester]
     * itself decides *which* book and *why*; this only decides whether it's
     * still valid to show right now.
     */
    val librarianSuggestion = combine(
        container.settingsRepository.librarianSuggestion,
        books,
    ) { suggestion, currentBooks ->
        if (suggestion == null || suggestion.dismissed) return@combine null
        if (suggestion.shownEpochDay != LocalDate.now().toEpochDay()) return@combine null
        suggestion.takeIf { s -> currentBooks.any { it.id == s.bookId } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissLibrarianSuggestion() {
        viewModelScope.launch { runCatching { container.settingsRepository.dismissLibrarianSuggestion() } }
    }

    init {
        // Fire-and-forget: a no-op with no AI configured, and throttled to a
        // few books per visit otherwise — see BookGenreClassifier's own doc.
        viewModelScope.launch { runCatching { container.bookGenreClassifier.classifyMissing() } }
        viewModelScope.launch {
            runCatching { container.librarianSuggester.refreshIfNeeded(LocalDate.now().toEpochDay()) }
        }
    }

    private val _reformatting = MutableStateFlow<Set<String>>(emptySet())
    val reformatting = _reformatting.asStateFlow()

    private val _reformatProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val reformatProgress = _reformatProgress.asStateFlow()

    fun hasRawBackup(book: BookEntity): Boolean = container.libraryRepository.rawBackupFile(book).exists()

    fun copyTranscript(book: BookEntity) {
        runCatching {
            val text = java.io.File(book.localPath).readText()
            val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(book.title, text))
        }
    }

    fun shareTranscript(book: BookEntity) {
        val context = getApplication<Application>()
        val intent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            putExtra(Intent.EXTRA_TEXT, runCatching { java.io.File(book.localPath).readText() }.getOrDefault(""))
        }, "Share transcript")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun replaceTranscript(bookId: String, editedText: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            container.libraryRepository.replaceTextTranscript(bookId, editedText)
                .onSuccess { onComplete() }
                .onFailure { _importError.value = it.message ?: "Could not replace transcript" }
        }
    }

    fun replaceTranscript(bookId: String, uri: Uri, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            container.libraryRepository.replaceTextTranscript(bookId, uri)
                .onSuccess { onComplete() }
                .onFailure { _importError.value = it.message ?: "Could not replace transcript" }
        }
    }

    fun restoreTranscript(bookId: String) {
        viewModelScope.launch {
            container.libraryRepository.restoreRawTranscript(bookId)
                .onFailure { _importError.value = it.message ?: "Could not restore original transcript" }
        }
    }

    fun delete(book: BookEntity) {
        viewModelScope.launch { container.libraryRepository.deleteBook(book) }
    }

    fun reformatWithAI(bookId: String) {
        if (bookId in _reformatting.value) return
        val gemini = container.geminiLearningClient
        if (!gemini.isConfigured) {
            _importError.value = "Set up a Gemini API key in Settings to use AI formatting"
            return
        }
        viewModelScope.launch {
            _reformatting.value += bookId
            _reformatProgress.value -= bookId
            _importError.value = null
            container.libraryRepository.reformatTranscriptWithAI(bookId, gemini) { completed, total ->
                _reformatProgress.value = _reformatProgress.value + (bookId to (completed to total))
            }
                .onFailure { error ->
                    _importError.value = error.message ?: "AI formatting failed"
                }
            _reformatting.value -= bookId
            _reformatProgress.value -= bookId
        }
    }

    fun importBook(uri: Uri, onImported: (BookEntity) -> Unit) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            _importError.value = null
            container.libraryRepository.importLocalBook(uri)
                .onSuccess(onImported)
                .onFailure { error ->
                    _importError.value = error.message ?: "Could not import this book"
                }
            _importing.value = false
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun importYouTubeUrl(url: String, onImported: (BookEntity) -> Unit) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            _importError.value = null
            container.libraryRepository.importYouTubeTranscript(url)
                .onSuccess(onImported)
                .onFailure { error ->
                    _importError.value = error.message ?: "Could not fetch YouTube transcript"
                }
            _importing.value = false
        }
    }
}

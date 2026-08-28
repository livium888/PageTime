package com.pagetime.app.ui.screens.library

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.flow.SharingStarted
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

    val balanceSeconds = container.balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastMapMoment = container.settingsRepository.lastMapMoment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalReadingSeconds = container.settingsRepository.settings
        .map { it.totalReadingSeconds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

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

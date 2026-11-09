package com.pagetime.app.ui.screens.concepts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.ConceptMapRepository
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConceptMapViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as PageTimeApp).container
    private val repository: ConceptMapRepository = container.conceptMapRepository

    private val _books = MutableStateFlow<List<BookEntity>>(emptyList())
    val books = _books.asStateFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)
    val selectedBookId = _selectedBookId.asStateFlow()

    private val _map = MutableStateFlow(ConceptMap(emptyList(), emptyList()))
    val map = _map.asStateFlow()

    private val _selectedConceptId = MutableStateFlow<String?>(null)
    val selectedConceptId = _selectedConceptId.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating = _generating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private var mapJob: Job? = null

    init {
        viewModelScope.launch {
            container.libraryRepository.observeBooks().collect { books ->
                _books.value = books
                if (_selectedBookId.value == null) books.firstOrNull()?.id?.let(::selectBook)
            }
        }
    }

    fun selectBook(bookId: String) {
        if (_selectedBookId.value == bookId) return
        _selectedBookId.value = bookId
        _selectedConceptId.value = null
        _map.value = ConceptMap(emptyList(), emptyList())
        mapJob?.cancel()
        mapJob = viewModelScope.launch {
            repository.observeBookMap(bookId).collect { _map.value = it }
        }
    }

    fun selectConcept(id: String?) {
        _selectedConceptId.value = id?.takeIf { it.isNotBlank() }
    }

    fun generateNow() {
        val bookId = _selectedBookId.value ?: return
        if (_generating.value) return
        viewModelScope.launch {
            _generating.value = true
            try {
                val book = _books.value.firstOrNull { it.id == bookId }
                repository.generateForReadingWindow(bookId, book?.currentChapterIndex ?: 0)
                _message.value = "Map updated from your latest reading context."
            } catch (error: Throwable) {
                _message.value = error.message ?: "Could not update the concept map"
            } finally {
                _generating.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** A richer map waiting for the reader's own AI app. */
    private val _pendingMap = MutableStateFlow<ConceptMapRepository.PendingMap?>(null)
    val pendingMap = _pendingMap.asStateFlow()

    /** Why the last pasted reply could not be used. Shown inside the ask dialog. */
    private val _mapAskError = MutableStateFlow<String?>(null)
    val mapAskError = _mapAskError.asStateFlow()

    /** Builds the prompt for the chapter the reader is in and opens the ask dialog. */
    fun askYourAi() {
        val bookId = _selectedBookId.value ?: return
        if (_generating.value) return
        viewModelScope.launch {
            _mapAskError.value = null
            try {
                val book = _books.value.firstOrNull { it.id == bookId }
                _pendingMap.value = repository.prepareManualMap(bookId, book?.currentChapterIndex ?: 0)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _message.value = error.message ?: "Could not prepare the prompt"
            }
        }
    }

    /** The reader pasted their AI app's reply to the pending map prompt. */
    fun submitMapReply(reply: String) {
        val pending = _pendingMap.value ?: return
        viewModelScope.launch {
            try {
                val merged = repository.mergeManualMap(pending, reply)
                _pendingMap.value = null
                _mapAskError.value = null
                _message.value = "Added ${merged.concepts.size} ideas and ${merged.relationships.size} connections."
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Stays in the dialog so the reader can copy the reply again.
                _mapAskError.value = error.message ?: "That reply couldn't be read. Try again."
            }
        }
    }

    fun dismissMapAsk() {
        _pendingMap.value = null
        _mapAskError.value = null
    }
}

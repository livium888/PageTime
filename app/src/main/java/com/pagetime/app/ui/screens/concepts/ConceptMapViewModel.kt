package com.pagetime.app.ui.screens.concepts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.ConceptMapRepository
import com.pagetime.app.data.local.BookEntity
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

    init {
        viewModelScope.launch {
            container.libraryRepository.observeBooks().collect { books ->
                _books.value = books
                if (_selectedBookId.value == null) books.firstOrNull()?.id?.let(::selectBook)
            }
        }
    }

    fun selectBook(bookId: String) {
        _selectedBookId.value = bookId
        viewModelScope.launch {
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
}

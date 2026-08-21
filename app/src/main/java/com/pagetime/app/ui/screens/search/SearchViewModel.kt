package com.pagetime.app.ui.screens.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository

    private val _results = MutableStateFlow<List<GutendexBook>>(emptyList())
    val results = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading = _downloading.asStateFlow()

    val downloadedIds = repo.observeBooks()
        .map { books -> books.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { repo.search(query) }
                .onSuccess { _results.value = it }
                .onFailure { _error.value = it.message ?: "Search failed" }
            _loading.value = false
        }
    }

    fun download(book: GutendexBook) {
        val id = book.id.toString()
        if (id in _downloading.value) return
        viewModelScope.launch {
            _downloading.value += id
            repo.downloadBook(book).onFailure { _error.value = it.message }
            _downloading.value -= id
        }
    }
}

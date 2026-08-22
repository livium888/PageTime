package com.pagetime.app.ui.screens.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Catalog sources available from the Discover screen. */
enum class BookSource {
    GUTENBERG,
    OPEN_LIBRARY
}

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository

    private val _books = MutableStateFlow<List<GutendexBook>>(emptyList())
    val books = _books.asStateFlow()

    private val _source = MutableStateFlow(BookSource.GUTENBERG)
    val source = _source.asStateFlow()

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading = _downloading.asStateFlow()

    val downloadedIds = repo.observeBooks()
        .map { books -> books.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var nextPage: Int? = null
    private var loadJob: Job? = null
    private var queryJob: Job? = null

    init {
        reload()
    }

    fun onSourceChange(value: BookSource) {
        if (value == _source.value) return
        _source.value = value
        _books.value = emptyList()
        reload()
    }

    fun onQueryChange(value: String) {
        _query.value = value
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            if (value.isBlank()) {
                reload()
            } else {
                delay(400)
                reload()
            }
        }
    }

    fun reload() = load(reset = true)

    fun retry() = load(reset = true)

    fun loadMore() {
        if (_loading.value || _loadingMore.value || !_hasMore.value) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val page = if (reset) 1 else (nextPage ?: return@launch)
            if (reset) {
                _loading.value = true
                _loadingMore.value = false
                _error.value = null
            } else {
                _loadingMore.value = true
            }
            try {
                val q = _query.value.trim()
                val src = _source.value
                runCatching {
                    if (src == BookSource.GUTENBERG) {
                        if (q.isBlank()) repo.browseGutenberg(page) else repo.searchGutenberg(q, page)
                    } else {
                        if (q.isBlank()) repo.browseOpenLibrary(page) else repo.searchOpenLibrary(q, page)
                    }
                }
                    .onSuccess { result ->
                        _books.value = if (reset) result.books else _books.value + result.books
                        _hasMore.value = result.hasNextPage
                        nextPage = if (result.hasNextPage) page + 1 else null
                    }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        _error.value = t.message ?: "Failed to load books"
                    }
            } finally {
                _loading.value = false
                _loadingMore.value = false
            }
        }
    }

    fun clearError() { _error.value = null }

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

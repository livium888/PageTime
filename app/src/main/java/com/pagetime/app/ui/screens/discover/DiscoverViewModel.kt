package com.pagetime.app.ui.screens.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.youtube.YouTubeSearchApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Catalog sources available from the Discover screen. */
enum class BookSource {
    STANDARD_EBOOKS,
    GUTENBERG,
    OPEN_LIBRARY,
    INTERNET_ARCHIVE,
    YOUTUBE
}

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository

    private val _books = MutableStateFlow<List<GutendexBook>>(emptyList())
    private val _searchingAll = MutableStateFlow(false)
    val searchingAll = _searchingAll.asStateFlow()
    val books = _books.asStateFlow()

    // Default to Standard Ebooks — it's the most reliable source (dedicated
    // servers, high-quality EPUBs, rarely rate-limited) so first-run download
    // always works. Users can switch to Gutenberg or Open Library if they want.
    private val _source = MutableStateFlow(BookSource.STANDARD_EBOOKS)
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

    /** Video IDs whose transcript import is in flight (Read button spinner). */
    private val _importingVideo = MutableStateFlow<Set<String>>(emptySet())
    val importingVideo = _importingVideo.asStateFlow()

    private val youtubeApi = container.youtubeSearchApi

    private val _youtubeResults = MutableStateFlow<List<YouTubeSearchApi.SearchResult>>(emptyList())
    val youtubeResults = _youtubeResults.asStateFlow()

    private val _categoryShelves = MutableStateFlow<List<YouTubeSearchApi.CategoryShelf>>(emptyList())
    val categoryShelves = _categoryShelves.asStateFlow()

    private val _loadingCategories = MutableStateFlow(false)
    val loadingCategories = _loadingCategories.asStateFlow()

    val downloadedIds = repo.observeBooks()
        .map { books -> books.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var nextPage: Int? = null
    private var loadJob: Job? = null
    private var queryJob: Job? = null

    init {
        reload()
    }

    fun searchAllSources() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val q = _query.value.trim()
            if (q.isBlank()) return@launch
            _searchingAll.value = true
            _loading.value = true
            _error.value = null
            try {
                val results = listOf(
                    async { runCatching { repo.searchGutenberg(q, 1).books } },
                    async { runCatching { repo.searchOpenLibrary(q, 1).books } },
                    async { runCatching { repo.searchStandardEbooks(q, 1).books } },
                    async { runCatching { repo.searchInternetArchive(q, 1).books } }
                ).awaitAll().flatMap { it.getOrElse { emptyList() } }
                    .filter { it.language == "en" }
                    .distinctBy { it.id }
                _books.value = results
                _source.value = BookSource.STANDARD_EBOOKS
                _hasMore.value = false
                nextPage = null
                if (results.isEmpty()) {
                    _error.value = "No English downloadable results found across the catalogs"
                }
            } finally {
                _loading.value = false
                _searchingAll.value = false
            }
        }
    }

    fun onSourceChange(value: BookSource) {
        if (value == _source.value) return
        _source.value = value
        _books.value = emptyList()
        _youtubeResults.value = emptyList()
        _categoryShelves.value = emptyList()
        if (value == BookSource.YOUTUBE && _query.value.isBlank()) {
            loadYouTubeBrowse()
        } else {
            reload()
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(400)
            if (value.isBlank()) {
                _books.value = emptyList()
                _youtubeResults.value = emptyList()
                _hasMore.value = false
                nextPage = null
                _error.value = "Enter a search query"
            } else if (_source.value == BookSource.YOUTUBE) {
                searchYouTube()
            } else {
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
        // YouTube uses its own search path (searchYouTube) — skip book loading.
        if (_source.value == BookSource.YOUTUBE) return
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
                    when (src) {
                        BookSource.GUTENBERG -> {
                            if (q.isBlank()) repo.browseGutenberg(page) else repo.searchGutenberg(q, page)
                        }
                        BookSource.OPEN_LIBRARY -> {
                            if (q.isBlank()) repo.browseOpenLibrary(page) else repo.searchOpenLibrary(q, page)
                        }
                        BookSource.STANDARD_EBOOKS -> {
                            if (q.isBlank()) repo.browseStandardEbooks(page) else repo.searchStandardEbooks(q, page)
                        }
                        BookSource.INTERNET_ARCHIVE -> {
                            if (q.isBlank()) error("Search Internet Archive by title or author")
                            else repo.searchInternetArchive(q, page)
                        }
                        BookSource.YOUTUBE -> error("unreachable")
                    }
                }
                    .onSuccess { result ->
                        // Dedupe by id: LazyColumn uses id as its item key and throws
                        // on duplicates, which can happen with hash-based source ids
                        // or overlapping pages.
                        val merged = if (reset) result.books else _books.value + result.books
                        _books.value = merged.distinctBy { it.id }
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

    /** Search YouTube for videos matching the current query. */
    fun searchYouTube() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val q = _query.value.trim()
            if (q.isBlank()) {
                // Empty query: show browse categories
                loadYouTubeBrowse()
                return@launch
            }
            _loading.value = true
            _error.value = null
            _categoryShelves.value = emptyList()
            try {
                val (results, _) = youtubeApi.search(q)
                _youtubeResults.value = results
                _books.value = emptyList()
                _hasMore.value = false
                nextPage = null
                if (results.isEmpty()) {
                    _error.value = "No YouTube videos found for this query"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "YouTube search failed"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Load browse/discovery categories when no search query is entered. */
    private fun loadYouTubeBrowse() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _youtubeResults.value = emptyList()
            _categoryShelves.value = emptyList()
            try {
                val shelves = youtubeApi.browseCategories()
                _categoryShelves.value = shelves
                if (shelves.isEmpty()) {
                    _error.value = "Could not load categories. Try searching instead."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load categories"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Import a YouTube video transcript as a readable book. */
    fun importYouTubeVideo(videoId: String) {
        if (videoId in _importingVideo.value) return
        viewModelScope.launch {
            _importingVideo.value += videoId
            val url = "https://www.youtube.com/watch?v=$videoId"
            repo.importYouTubeTranscript(url)
                .onFailure { _error.value = it.message ?: "Could not fetch transcript" }
            _importingVideo.value -= videoId
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

package com.pagetime.app.ui.screens.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.catalog.BookCatalog
import com.pagetime.app.data.catalog.CatalogHealth
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

/**
 * A chip in Discover: one of the book catalogues, or the video importer.
 *
 * Videos are not a catalogue — nothing about them is a book page, and the load
 * path skips them entirely — so they sit beside the catalogues rather than
 * being forced to implement one.
 */
sealed interface DiscoverSource {
    val id: String
    val label: String
    val searchHint: String

    data class Books(val catalog: BookCatalog) : DiscoverSource {
        override val id: String get() = catalog.id
        override val label: String get() = catalog.label
        override val searchHint: String get() = "Search ${catalog.label}\u2026"
    }

    data object Videos : DiscoverSource {
        override val id = "youtube"
        override val label = "YouTube"
        override val searchHint = "Search YouTube videos\u2026"
    }
}

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.libraryRepository

    private val _books = MutableStateFlow<List<GutendexBook>>(emptyList())
    private val _searchingAll = MutableStateFlow(false)
    val searchingAll = _searchingAll.asStateFlow()
    val books = _books.asStateFlow()

    private val catalogs = container.bookCatalogs

    /** Every chip, catalogues first and videos last. */
    val sources: List<DiscoverSource> =
        catalogs.all.map { DiscoverSource.Books(it) } + DiscoverSource.Videos

    // Defaults to whichever catalogue the registry leads with — Standard Ebooks,
    // the most reliable of them, so a first run always works.
    private val _source = MutableStateFlow<DiscoverSource>(DiscoverSource.Books(catalogs.default))
    val source = _source.asStateFlow()

    /**
     * Why the shelf looks the way it does. Kept apart from [error], which is
     * the transient message a download or an import puts in a snackbar: a
     * catalogue that did not answer is a state of the screen, not a passing
     * notice, and the two were the same field for long enough that a dead
     * source and an empty search were drawn identically.
     */
    private val _health = MutableStateFlow<CatalogHealth>(CatalogHealth.Working)
    val health = _health.asStateFlow()

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
            try {
                // Every catalogue is asked, and which ones failed is kept. This
                // used to be getOrElse { emptyList() }, so a catalogue that was
                // down contributed nothing and said nothing, and a search of
                // four sources that reached two looked exactly like a search of
                // four that found little.
                val outcomes = catalogs.all
                    .map { catalog -> catalog to async { runCatching { catalog.search(q, 1).books } } }
                    .map { (catalog, job) -> catalog to job.await() }
                val silent = outcomes.filter { it.second.isFailure }.map { it.first.label }
                val results = outcomes
                    .mapNotNull { it.second.getOrNull() }
                    .flatten()
                    .filter { it.language == "en" }
                    .distinctBy { it.id }
                _books.value = results
                _source.value = DiscoverSource.Books(catalogs.default)
                _hasMore.value = false
                nextPage = null
                _health.value = when {
                    results.isNotEmpty() && silent.isEmpty() -> CatalogHealth.Working
                    results.isNotEmpty() -> CatalogHealth.PartlyReachable(silent)
                    silent.size == catalogs.all.size ->
                        CatalogHealth.Unreachable(
                            "Every catalogue",
                            "None of them answered. Check your connection."
                        )
                    else -> CatalogHealth.NothingMatched(
                        label = "Every catalogue",
                        query = q,
                        note = if (silent.isEmpty()) "" else "${silent.joinToString(", ")} did not answer."
                    )
                }
            } finally {
                _loading.value = false
                _searchingAll.value = false
            }
        }
    }

    fun onSourceChange(value: DiscoverSource) {
        if (value == _source.value) return
        _source.value = value
        _books.value = emptyList()
        _youtubeResults.value = emptyList()
        _categoryShelves.value = emptyList()
        _health.value = CatalogHealth.Working
        if (value is DiscoverSource.Videos && _query.value.isBlank()) {
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
            // Clearing the box goes back to browsing. It used to say "Enter a
            // search query" instead, on catalogues that browse perfectly well
            // without one — a blank shelf with a message that was not true of
            // the source the reader was looking at.
            if (_source.value is DiscoverSource.Videos) {
                if (value.isBlank()) loadYouTubeBrowse() else searchYouTube()
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
        // Videos have their own search path (searchYouTube) — no book loading.
        val catalog = (_source.value as? DiscoverSource.Books)?.catalog ?: return
        val q = _query.value.trim()
        if (q.isBlank() && !catalog.browsable) {
            // Not a failure and not an empty result: this catalogue has no
            // browse endpoint at all, which the reader can act on if told.
            _books.value = emptyList()
            _hasMore.value = false
            nextPage = null
            _health.value = CatalogHealth.NeedsQuery(catalog.label, catalog.note)
            return
        }
        loadJob = viewModelScope.launch {
            val page = if (reset) 1 else (nextPage ?: return@launch)
            if (reset) {
                _loading.value = true
                _loadingMore.value = false
            } else {
                _loadingMore.value = true
            }
            try {
                runCatching {
                    if (q.isBlank()) catalog.browse(page) else catalog.search(q, page)
                }
                    .onSuccess { result ->
                        // Dedupe by id: LazyColumn uses id as its item key and throws
                        // on duplicates, which can happen with hash-based source ids
                        // or overlapping pages.
                        val merged = if (reset) result.books else _books.value + result.books
                        val books = merged.distinctBy { it.id }
                        _books.value = books
                        _hasMore.value = result.hasNextPage
                        nextPage = if (result.hasNextPage) page + 1 else null
                        _health.value = if (books.isNotEmpty()) {
                            CatalogHealth.Working
                        } else {
                            CatalogHealth.NothingMatched(catalog.label, q, catalog.note)
                        }
                    }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        // The distinction the screen was missing: this source
                        // did not answer, which is not the same as having
                        // nothing to show.
                        if (reset) {
                            _health.value = CatalogHealth.Unreachable(
                                catalog.label,
                                t.message ?: "It did not answer."
                            )
                        } else {
                            _error.value = t.message ?: "Could not load more from ${catalog.label}"
                        }
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
            _categoryShelves.value = emptyList()
            try {
                val (results, _) = youtubeApi.search(q)
                _youtubeResults.value = results
                _books.value = emptyList()
                _hasMore.value = false
                nextPage = null
                _health.value = if (results.isNotEmpty()) {
                    CatalogHealth.Working
                } else {
                    CatalogHealth.NothingMatched("YouTube", q, "Transcripts become readable books.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _health.value = CatalogHealth.Unreachable(
                    "YouTube",
                    e.message ?: "It did not answer."
                )
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
            _youtubeResults.value = emptyList()
            _categoryShelves.value = emptyList()
            try {
                val shelves = youtubeApi.browseCategories()
                _categoryShelves.value = shelves
                _health.value = if (shelves.isNotEmpty()) {
                    CatalogHealth.Working
                } else {
                    CatalogHealth.Unreachable("YouTube", "Could not load categories. Try searching instead.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _health.value = CatalogHealth.Unreachable(
                    "YouTube",
                    e.message ?: "Could not load categories."
                )
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

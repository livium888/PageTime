package com.pagetime.app.ui.screens.highlights

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.TextHighlightEntity
import com.pagetime.app.data.local.isReadiumBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HighlightRow(
    val highlight: TextHighlightEntity,
    /** How far into the book it sits, e.g. "34% in"; null when unknowable. */
    val whereLabel: String?
)

data class HighlightsUiState(
    val bookTitle: String = "",
    val rows: List<HighlightRow> = emptyList(),
    val isTextBook: Boolean = true,
    val loading: Boolean = true
)

/**
 * The reader's saved highlights for one book.
 *
 * Highlights were stored, drawn in the book and then unreachable: nothing could
 * list them, open them or remove them. This is the surface that turns them into
 * a feature rather than a mark on the page.
 */
class HighlightsViewModel(
    app: Application,
    private val bookId: String
) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.highlightRepository
    private val bookDao = container.database.bookDao()

    private val _state = MutableStateFlow(HighlightsUiState())
    val state: StateFlow<HighlightsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val book = bookDao.getById(bookId)
            val isText = book?.isReadiumBook != true
            // A plain-text percentage needs the book's length. One read for the
            // whole list, and skipped entirely for EPUBs, whose locators carry
            // their own progression.
            val length = if (isText) repo.textLength(bookId) else null
            repo.observeForBook(bookId).collect { highlights ->
                _state.value = HighlightsUiState(
                    bookTitle = book?.title ?: "Book",
                    isTextBook = isText,
                    rows = highlights.map { HighlightRow(it, whereLabel(it, length)) },
                    loading = false
                )
            }
        }
    }

    private fun whereLabel(highlight: TextHighlightEntity, txtLength: Int?): String? = when {
        highlight.kind == "epub" ->
            epubProgression(highlight.startLocatorJson)?.let { "${(it * 100).toInt()}% in" }
        txtLength != null && highlight.startOffset >= 0 ->
            "${(highlight.startOffset.toFloat() / txtLength.toFloat() * 100).toInt()}% in"
        else -> null
    }

    /** Readium locators carry a whole-book progression; a hand-made one may not. */
    private fun epubProgression(locatorJson: String?): Float? {
        val json = locatorJson ?: return null
        if (json.isBlank()) return null
        return runCatching {
            JSONObject(json)
                .optJSONObject("locations")
                ?.optDouble("progression", Double.NaN)
                ?.takeIf { !it.isNaN() }
                ?.toFloat()
        }.getOrNull()
    }

    /**
     * Aims the reader at the highlight, then opens the book.
     *
     * [onOpened] fires only after the pending-source write has landed, so the
     * reader cannot load before the target position exists.
     */
    fun open(highlight: TextHighlightEntity, onOpened: () -> Unit) {
        viewModelScope.launch {
            repo.aimAt(highlight)
            onOpened()
        }
    }

    fun delete(highlight: TextHighlightEntity) {
        viewModelScope.launch { repo.delete(highlight.id) }
    }
}

class HighlightsViewModelFactory(
    private val app: Application,
    private val bookId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HighlightsViewModel(app, bookId) as T
}

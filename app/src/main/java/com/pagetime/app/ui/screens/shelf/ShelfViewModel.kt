package com.pagetime.app.ui.screens.shelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.shelf.ReadingLadders
import com.pagetime.app.data.shelf.ShelfRow
import com.pagetime.app.data.shelf.ShelfRows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShelfViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.shelfRepository

    val rows = repo.observeShelf(ReadingLadders.GREAT_BOOKS_SHELF_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readCount = rows.map { list -> ShelfRows.completed(list.map { it.state }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Slots with a download in flight, so a row can say so. */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading = _downloading.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            repo.seedGreatBooks()
            // Resolve in passes rather than all at once: sixty searches on
            // first open is a burst of network nobody asked for, and the shelf
            // has something to show from the first row onward.
            _checking.value = true
            try {
                while (repo.resolveSome() > 0) { /* keep going while there is work */ }
            } finally {
                _checking.value = false
            }
        }
    }

    fun download(row: ShelfRow) {
        if (row.slotId in _downloading.value) return
        _downloading.value = _downloading.value + row.slotId
        viewModelScope.launch {
            val result = repo.download(ReadingLadders.GREAT_BOOKS_SHELF_ID, row.slotId)
            _downloading.value = _downloading.value - row.slotId
            result.exceptionOrNull()?.let {
                _error.value = "Could not download ${row.title}: ${it.message ?: "unknown error"}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

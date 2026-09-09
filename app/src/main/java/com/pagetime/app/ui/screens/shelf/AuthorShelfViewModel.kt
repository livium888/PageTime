package com.pagetime.app.ui.screens.shelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.shelf.AuthorLookup
import com.pagetime.app.data.shelf.ShelfRow
import com.pagetime.app.data.shelf.ShelfRowState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorShelfViewModel(
    app: Application,
    private val authorName: String,
) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.shelfRepository
    private val shelfId = repo.authorShelfId(authorName)

    val rows = repo.observeShelf(shelfId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many of this author's books are already on the shelf downloaded. */
    val ownedCount = MutableStateFlow(0)

    private val _lookup = MutableStateFlow<AuthorLookup?>(null)
    val lookup = _lookup.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading = _downloading.asStateFlow()

    init {
        viewModelScope.launch {
            _loading.value = true
            try {
                _lookup.value = repo.buildAuthorShelf(authorName)
                // Only worth asking the catalogues once there is something to
                // ask about; an ambiguous or missing author wrote no rows.
                if (_lookup.value is AuthorLookup.Found) {
                    while (repo.resolveSome(shelfId) > 0) { /* until nothing is pending */ }
                }
            } finally {
                _loading.value = false
            }
        }
        viewModelScope.launch {
            rows.collect { list ->
                ownedCount.value = list.count { it.state is ShelfRowState.Owned }
            }
        }
    }

    fun download(row: ShelfRow) {
        if (row.slotId in _downloading.value) return
        _downloading.value = _downloading.value + row.slotId
        viewModelScope.launch {
            repo.download(shelfId, row.slotId)
            _downloading.value = _downloading.value - row.slotId
        }
    }

    class Factory(
        private val app: Application,
        private val authorName: String,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            AuthorShelfViewModel(app, authorName) as T
    }
}

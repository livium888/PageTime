package com.pagetime.app.ui.screens.pagemarks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.PagemarkSession
import com.pagetime.app.data.local.PagemarkEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PagemarkRow(
    val pagemark: PagemarkEntity,
    val bookTitle: String
)

data class PagemarkQueueUiState(
    val rows: List<PagemarkRow> = emptyList(),
    val dueCount: Int = 0,
    val loading: Boolean = true
)

class PagemarkQueueViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.pagemarkRepository
    private val bookDao = container.database.bookDao()

    val state = combine(
        repo.observeAll(),
        bookDao.observeAll()
    ) { pagemarks, books ->
        val titles = books.associate { it.id to it.title }
        val now = System.currentTimeMillis()
        PagemarkQueueUiState(
            rows = PagemarkSession.orderForQueue(pagemarks, now).map { pm ->
                PagemarkRow(
                    pagemark = pm,
                    bookTitle = titles[pm.bookId] ?: "Book"
                )
            },
            dueCount = PagemarkSession.dueCount(pagemarks, now),
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagemarkQueueUiState())

    /**
     * Re-opens the chunk and aims the reader at its start.
     *
     * [onOpened] fires after the pending-source write lands, so the reader
     * cannot load before the chunk start exists and open at the wrong place.
     */
    fun open(pagemark: PagemarkEntity, onOpened: () -> Unit = {}) {
        viewModelScope.launch {
            repo.resumeChunk(pagemark.id)
            onOpened()
        }
    }

    fun raisePriority(pagemark: PagemarkEntity) {
        viewModelScope.launch { repo.setPriority(pagemark.id, pagemark.priority + 1) }
    }

    fun lowerPriority(pagemark: PagemarkEntity) {
        viewModelScope.launch { repo.setPriority(pagemark.id, pagemark.priority - 1) }
    }

    fun delete(pagemark: PagemarkEntity) {
        viewModelScope.launch { repo.delete(pagemark.id) }
    }
}
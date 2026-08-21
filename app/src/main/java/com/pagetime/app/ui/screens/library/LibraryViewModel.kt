package com.pagetime.app.ui.screens.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container

    val books = container.libraryRepository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val balanceSeconds = container.balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun delete(book: BookEntity) {
        viewModelScope.launch { container.libraryRepository.deleteBook(book) }
    }
}

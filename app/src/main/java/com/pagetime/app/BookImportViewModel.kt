package com.pagetime.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the outcome of a book delivered from outside the app (ACTION_VIEW or
 * ACTION_SEND with a stream). The ViewModel is activity-scoped, so an import
 * in flight survives configuration changes, and the handled-URI guard keeps a
 * re-delivered launch intent from importing the same file twice.
 */
class BookImportViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Idle : State
        data object Importing : State
        data class Done(val book: BookEntity) : State
        data class Failed(val message: String) : State
    }

    private val container = (app as PageTimeApp).container

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var lastHandledUri: Uri? = null

    /** Starts importing [uri] unless this exact delivery was already handled. */
    fun onIncomingUri(uri: Uri) {
        if (_state.value is State.Importing || uri == lastHandledUri) return
        lastHandledUri = uri
        _state.value = State.Importing
        viewModelScope.launch {
            container.libraryRepository.importLocalBook(uri)
                .onSuccess { _state.value = State.Done(it) }
                .onFailure { error ->
                    _state.value = State.Failed(error.message ?: "Could not import this book")
                }
        }
    }

    /** Clears the handled state after the UI has reacted (navigation or snackbar). */
    fun consume() {
        _state.value = State.Idle
    }
}

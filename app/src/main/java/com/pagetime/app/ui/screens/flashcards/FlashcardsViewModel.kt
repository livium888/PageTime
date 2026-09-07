package com.pagetime.app.ui.screens.flashcards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.LearningCardEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FlashcardsUiState(
    val groups: List<FlashcardGroup> = emptyList(),
    val counts: Map<FlashcardFilter, Int> = emptyMap(),
    val filter: FlashcardFilter = FlashcardFilter.ALL,
    val loading: Boolean = true,
) {
    val total: Int get() = counts[FlashcardFilter.ALL] ?: 0
    val due: Int get() = counts[FlashcardFilter.DUE] ?: 0
}

class FlashcardsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val cardDao = container.database.learningCardDao()
    private val bookDao = container.database.bookDao()
    private val generator = container.chapterPromptGenerator

    private val _filter = MutableStateFlow(FlashcardFilter.ALL)
    val filter = _filter.asStateFlow()

    val state = combine(
        cardDao.observeLive(),
        bookDao.observeAll(),
        _filter,
    ) { cards, books, filter ->
        val titles = books.associate { it.id to it.title }
        // Counts are computed from the same list the groups come from, at the
        // same instant. Two passes over different snapshots would let a chip
        // say 3 and the list show 2.
        val now = System.currentTimeMillis()
        FlashcardsUiState(
            groups = FlashcardListing.group(cards, titles, filter, now),
            counts = FlashcardFilter.entries.associateWith {
                FlashcardListing.countFor(cards, it, now)
            },
            filter = filter,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlashcardsUiState())

    fun setFilter(filter: FlashcardFilter) {
        _filter.value = filter
    }

    /** Accepts a card that was still waiting to be judged. */
    fun keep(card: LearningCardEntity) {
        viewModelScope.launch { runCatching { generator.keep(card.id) } }
    }

    /**
     * Throws a card away.
     *
     * Marked skipped rather than deleted, for the same reason as in the reader:
     * a deleted row lets the chapter be generated again and offer the same
     * rejected question straight back.
     */
    fun discard(card: LearningCardEntity) {
        viewModelScope.launch { runCatching { generator.skip(card.id) } }
    }
}

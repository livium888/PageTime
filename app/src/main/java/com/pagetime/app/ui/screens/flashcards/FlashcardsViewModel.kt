package com.pagetime.app.ui.screens.flashcards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.ReviewTally
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A book/chapter the "Explain" button can jump straight into — see [FlashcardsUiState.nextDueConcept]. */
data class DueConceptEntry(
    val bookId: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
)

data class FlashcardsUiState(
    val groups: List<FlashcardGroup> = emptyList(),
    val counts: Map<FlashcardFilter, Int> = emptyMap(),
    val filter: FlashcardFilter = FlashcardFilter.ALL,
    /**
     * What the reader has actually remembered, from the review log.
     *
     * Not derivable from the cards themselves: grading overwrites a card's
     * state, so without the log the app can say what is scheduled and nothing
     * at all about whether any of it worked.
     */
    val tally: ReviewTally = ReviewTally(),
    /** How many concepts, across every book, have never been explained — see [FlashcardsViewModel]'s concept queue. */
    val dueConceptCount: Int = 0,
    /** Where the "Explain N" button jumps to: the single most overdue concept, in whichever book it lives in. */
    val nextDueConcept: DueConceptEntry? = null,
    val loading: Boolean = true,
    /** ID of the card currently being edited, or null. */
    val editingCardId: String? = null,
) {
    val total: Int get() = counts[FlashcardFilter.ALL] ?: 0
    val due: Int get() = counts[FlashcardFilter.DUE] ?: 0
}

class FlashcardsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val cardDao = container.database.learningCardDao()
    private val reviewLog = container.database.learningReviewLogDao()
    private val bookDao = container.database.bookDao()
    private val generator = container.chapterPromptGenerator
    private val explainBackRepository = container.explainBackRepository

    private val _filter = MutableStateFlow(FlashcardFilter.ALL)
    val filter = _filter.asStateFlow()

    private val _editingCardId = MutableStateFlow<String?>(null)
    val editingCardId = _editingCardId.asStateFlow()

    val state = combine(
        combine(
            cardDao.observeLive(),
            bookDao.observeAll(),
            _filter,
            reviewLog.observeTally(),
            explainBackRepository.observeConceptQueue(),
        ) { cards, books, filter, tally, conceptQueue ->
            val titles = books.associate { it.id to it.title }
            val now = System.currentTimeMillis()
            FlashcardsUiState(
                groups = FlashcardListing.group(cards, titles, filter, now),
                counts = FlashcardFilter.entries.associateWith {
                    FlashcardListing.countFor(cards, it, now)
                },
                filter = filter,
                tally = tally,
                dueConceptCount = conceptQueue.unexplainedCount,
                // No chapter title is stored on a concept, only the chapter index
                // it was found in — same fallback ChapterReviewPrompt uses for the
                // same reason.
                nextDueConcept = conceptQueue.next?.let { due ->
                    DueConceptEntry(
                        bookId = due.bookId,
                        bookTitle = titles[due.bookId] ?: "Book",
                        chapterIndex = due.chapterIndex,
                        chapterTitle = "Chapter ${due.chapterIndex + 1}",
                    )
                },
                loading = false,
            )
        },
        _editingCardId,
    ) { current, editingCardId ->
        current.copy(editingCardId = editingCardId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlashcardsUiState())

    fun setFilter(filter: FlashcardFilter) {
        _filter.value = filter
    }

    /** Accepts a card that was still waiting to be judged. */
    fun keep(card: LearningCardEntity) {
        viewModelScope.launch { runCatching { generator.keep(card.id) } }
    }

    /** Throw away — marks as skipped, not deleted, so chapter is not regenerated. */
    fun discard(card: LearningCardEntity) {
        viewModelScope.launch { runCatching { generator.skip(card.id) } }
    }

    /** Permanently delete a card from the database. */
    fun delete(card: LearningCardEntity) {
        viewModelScope.launch {
            runCatching { cardDao.delete(card.id) }
        }
    }

    /** Enter edit mode for a card. */
    fun startEditing(card: LearningCardEntity) {
        _editingCardId.value = card.id
    }

    /** Exit edit mode without saving. */
    fun cancelEditing() {
        _editingCardId.value = null
    }

    /** Save edited prompt and answer for a card. */
    fun saveEdit(card: LearningCardEntity, newPrompt: String, newAnswer: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                cardDao.upsert(
                    card.copy(
                        prompt = newPrompt.trim(),
                        answer = newAnswer.trim(),
                        updatedAt = now,
                    )
                )
            }
            _editingCardId.value = null
        }
    }
}

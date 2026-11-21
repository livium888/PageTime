package com.pagetime.app.ui.screens.flashcards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.ReviewTally
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    /** What is typed in the search box, and the one book being looked at (null for all). */
    val query: String = "",
    val bookId: String? = null,
    /** Books that have cards, for the book picker: id to title. */
    val bookChoices: List<Pair<String, String>> = emptyList(),
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
    /** Notes in the slip box, which are reviewed in the same sitting as the cards. */
    val slipCount: Int = 0,
    /** How many of those notes are due now. */
    val slipsDue: Int = 0,
) {
    val total: Int get() = counts[FlashcardFilter.ALL] ?: 0
    val due: Int get() = counts[FlashcardFilter.DUE] ?: 0

    /**
     * Everything the review sitting will ask: due cards and due notes.
     *
     * The sitting has always included notes; the headline number did not, so it
     * promised fewer questions than the reader then got.
     */
    val dueTotal: Int get() = due + slipsDue
}

class FlashcardsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val cardDao = container.database.learningCardDao()
    private val reviewLog = container.database.learningReviewLogDao()
    private val bookDao = container.database.bookDao()
    private val lumenDao = container.database.lumenCardDao()
    private val generator = container.chapterQuestions
    private val explainBackRepository = container.explainBackRepository

    private val _filter = MutableStateFlow(FlashcardFilter.ALL)
    val filter = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    private val _bookId = MutableStateFlow<String?>(null)
    /** What is being looked at: status filter, search words and book, as one value. */
    private data class Lens(val filter: FlashcardFilter, val query: String, val bookId: String?)
    private val lens = combine(_filter, _query, _bookId) { f, q, b -> Lens(f, q, b) }

    private val _editingCardId = MutableStateFlow<String?>(null)
    val editingCardId = _editingCardId.asStateFlow()

    val state = combine(
        combine(
            cardDao.observeLive(),
            bookDao.observeAll(),
            lens,
            reviewLog.observeTally(),
            explainBackRepository.observeConceptQueue(),
        ) { cards, books, lens, tally, conceptQueue ->
            val filter = lens.filter
            // A book with no cards left cannot stay chosen: nothing would show
            // and the picker, with one book, is hidden.
            val bookId = lens.bookId?.takeIf { id -> cards.any { it.bookId == id } }
            val titles = books.associate { it.id to it.title }
            val now = System.currentTimeMillis()
            FlashcardsUiState(
                groups = FlashcardListing.group(cards, titles, filter, now, lens.query, bookId),
                query = lens.query,
                bookId = bookId,
                bookChoices = cards.map { it.bookId }.distinct()
                    .map { it to (titles[it] ?: "A book no longer in the library") }
                    .sortedBy { it.second.lowercase() },
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
        lumenDao.observeAll(),
    ) { current, editingCardId, slips ->
        val now = System.currentTimeMillis()
        current.copy(
            editingCardId = editingCardId,
            slipCount = slips.size,
            slipsDue = slips.count { it.dueAt != null && it.dueAt <= now },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlashcardsUiState())

    fun setFilter(filter: FlashcardFilter) {
        _filter.value = filter
    }

    /** Whether new cards are being sent to Anki, how many have been, and what went wrong. */
    data class AnkiInfo(val on: Boolean = false, val sent: Int = 0, val problem: String? = null)

    val anki: StateFlow<AnkiInfo> = combine(
        container.settingsRepository.ankiSendSince,
        container.settingsRepository.ankiSentIds,
        container.ankiPusher.problem,
    ) { since, sent, problem ->
        AnkiInfo(on = since > 0L, sent = com.pagetime.app.anki.AnkiNotes.decodeSent(sent).size, problem = problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnkiInfo())

    /** Starts sending from now on: the cards already made stay where they are. */
    fun startSendingToAnki() {
        viewModelScope.launch { container.settingsRepository.setAnkiSendSince(System.currentTimeMillis()) }
    }

    fun stopSendingToAnki() {
        viewModelScope.launch { container.settingsRepository.setAnkiSendSince(0L) }
    }

    fun retrySendingToAnki() = container.ankiPusher.retry()

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setBook(bookId: String?) {
        _bookId.value = bookId
    }

    /**
     * Takes cards out of the list the way "Throw it away" does: marked skipped,
     * not erased, so a chapter is not asked for its questions all over again
     * and the review history stays. [restore] puts them back as they were.
     */
    fun remove(cards: List<LearningCardEntity>) {
        viewModelScope.launch {
            cards.forEach { runCatching { generator.skip(it.id) } }
        }
    }

    /** Undo of [remove]: each card goes back to the status it had. */
    fun restore(cards: List<LearningCardEntity>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            cards.forEach { runCatching { cardDao.setStatus(it.id, it.status, now) } }
        }
    }

    /** Accepts every card in the list that was still waiting to be judged. */
    fun keepAll(cards: List<LearningCardEntity>) {
        viewModelScope.launch {
            cards.filter { it.status == LearningCardEntity.STATUS_PENDING }
                .forEach { runCatching { generator.keep(it.id) } }
        }
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

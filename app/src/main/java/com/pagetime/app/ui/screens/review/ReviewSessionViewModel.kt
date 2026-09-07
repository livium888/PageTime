package com.pagetime.app.ui.screens.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.LumenCardEntity
import io.github.openspacedrepetition.Scheduler
import com.pagetime.app.data.review.ReviewSession
import com.pagetime.app.data.review.ReviewSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * One thing to answer, whatever kind of card it came from.
 *
 * Two card types are reviewed here and they are genuinely different: a
 * flashcard generated from a chapter is a question with an answer, and a slip
 * box note is an idea the reader chose to carry. The session does not care —
 * both are a front, a back, and something the reader can be shown to prove the
 * card is not invented.
 */
data class ReviewItem(
    val id: String,
    val front: String,
    val back: String,
    val source: String?,
    val bookId: String,
    /** Where the reader's own note came from, versus a generated question. */
    val fromChapter: Boolean,
)

data class ReviewUiState(
    val loading: Boolean = true,
    val session: ReviewSessionState = ReviewSessionState(emptyList()),
    val card: ReviewItem? = null,
    val revealed: Boolean = false,
    /** What the scheduler decided after the last answer, for a moment's feedback. */
    val lastInterval: String? = null,
)

class ReviewSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repository = container.lumenRepository
    private val learningCards = container.database.learningCardDao()

    /**
     * Its own scheduler instance, matching the one LumenRepository builds.
     *
     * Flashcards have no repository of their own yet, so grading one happens
     * here. The important thing is that both card types go through the SAME
     * FSRS configuration — two schedulers with different parameters would give
     * the same reader different intervals depending on where a card came from.
     */
    private val scheduler: Scheduler = Scheduler.builder().build()

    private val _state = MutableStateFlow(ReviewUiState())
    val state = _state.asStateFlow()

    /** Cards held for the whole sitting; the session itself only carries ids. */
    private var cards: Map<String, ReviewItem> = emptyMap()

    /** Which table each id came from, so the right one is graded. */
    private var chapterCardIds: Set<String> = emptySet()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val now = Instant.now()
            // The lookahead is why this asks for a later instant than now: a
            // card falling due this evening should be answered while the
            // reader is here.
            val threshold = ReviewSession.dueThreshold(now.toEpochMilli())

            // Chapter flashcards first. They are the point of the feature, and
            // a reader who has both should not have to wade through slip box
            // notes to reach them.
            val chapter = runCatching {
                learningCards.dueCards(threshold, ReviewSession.MAX_SESSION)
            }.getOrDefault(emptyList())

            val slips = runCatching {
                repository.dueCards(
                    now = Instant.ofEpochMilli(threshold),
                    limit = ReviewSession.MAX_SESSION - chapter.size,
                )
            }.getOrDefault(emptyList())

            chapterCardIds = chapter.map { it.id }.toSet()
            cards = (chapter.map { it.asReviewItem() } + slips.map { it.asReviewItem() })
                .associateBy { it.id }

            val session = ReviewSession.start(chapter.map { it.id } + slips.map { it.id })
            _state.value = ReviewUiState(
                loading = false,
                session = session,
                card = session.current?.let { cards[it] },
                revealed = false,
            )
        }
    }

    private fun LearningCardEntity.asReviewItem() = ReviewItem(
        id = id,
        front = prompt,
        back = answer,
        source = sourceQuote,
        bookId = bookId,
        fromChapter = true,
    )

    private fun LumenCardEntity.asReviewItem(): ReviewItem {
        val (front, back) = repository.trainingPrompt(this)
        return ReviewItem(
            id = id,
            front = front,
            back = back,
            source = quote.takeIf { it.isNotBlank() && it != back },
            bookId = bookId,
            fromChapter = false,
        )
    }

    /**
     * Applies a rating to a chapter flashcard.
     *
     * The same two writes LumenRepository.rateTraining does: the scheduler's
     * new state as JSON, and dueAt lifted out of it so the due query stays a
     * WHERE clause.
     */
    private suspend fun gradeChapterCard(id: String, rating: LumenRating, now: Instant): Instant? {
        val existing = learningCards.get(id) ?: return null
        val old = runCatching { FsrsCardCodec.fromJson(existing.fsrsCardJson) }.getOrNull() ?: return null
        val result = scheduler.reviewCard(old, rating.toFsrs(), now, null)
        val updated = result.card()
        val nextDue = updated.due ?: now.plusSeconds(86_400)
        learningCards.upsert(
            existing.copy(
                fsrsCardJson = FsrsCardCodec.toJson(updated),
                dueAt = nextDue.toEpochMilli(),
                reviewCount = existing.reviewCount + 1,
                lastRating = rating.value,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return nextDue
    }

    fun reveal() {
        _state.value = _state.value.copy(revealed = true, lastInterval = null)
    }

    /**
     * Records an answer.
     *
     * Two different things happen and they must not be confused: FSRS is told
     * about the rating and decides when this card comes back on a later day,
     * while the sitting decides only whether it comes back in the next few
     * minutes. A failed card does both — it is rescheduled AND requeued.
     */
    fun grade(rating: LumenRating) {
        val current = _state.value.card ?: return
        viewModelScope.launch {
            val now = Instant.now()
            val nextDue = runCatching {
                if (current.id in chapterCardIds) {
                    gradeChapterCard(current.id, rating, now)
                } else {
                    repository.rateTraining(current.id, rating, now)
                }
            }.getOrNull()
            val advanced = ReviewSession.grade(_state.value.session, failed = rating == LumenRating.AGAIN)
            _state.value = _state.value.copy(
                session = advanced,
                card = advanced.current?.let { cards[it] },
                revealed = false,
                lastInterval = nextDue?.let { formatNextReview(it) },
            )
        }
    }

    /** Leaves the card for another day: no rating, so the scheduler is untouched. */
    fun skip() {
        val advanced = ReviewSession.skip(_state.value.session)
        _state.value = _state.value.copy(
            session = advanced,
            card = advanced.current?.let { cards[it] },
            revealed = false,
            lastInterval = null,
        )
    }

}

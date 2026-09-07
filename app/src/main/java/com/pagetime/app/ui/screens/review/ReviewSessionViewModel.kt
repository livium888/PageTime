package com.pagetime.app.ui.screens.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.LearningReviewLogEntity
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
    /** Whether the last answer can still be taken back. */
    val canUndo: Boolean = false,
)

class ReviewSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repository = container.lumenRepository
    private val learningCards = container.database.learningCardDao()
    private val reviewLog = container.database.learningReviewLogDao()
    private val settings = container.settingsRepository

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

    /**
     * How to take back the last answer.
     *
     * One step, not a stack. The mistake this exists for is a mis-tap on the
     * card in front of you — hitting "Again" on one you knew — and that is
     * noticed immediately or not at all. A deeper history would be a promise
     * the reader has no way to navigate.
     *
     * Undo matters more here than in most places because a rating is not a
     * display state: it permanently rewrites the card's difficulty and
     * stability, and Again on a well-known card costs weeks of interval that
     * nothing else can give back.
     */
    private class UndoStep(
        val session: ReviewSessionState,
        val card: ReviewItem?,
        val restore: suspend () -> Unit,
    )

    private var undoStep: UndoStep? = null

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

    private fun LearningCardEntity.asReviewItem(): ReviewItem {
        // A cloze is shown as its sentence with a gap, and revealed as the same
        // sentence whole — never as the stored {{c1::…}} markup.
        val isCloze = cardType == LearningCardEntity.TYPE_CLOZE
        return ReviewItem(
            id = id,
            front = if (isCloze) ClozeText.blanked(prompt) else prompt,
            back = if (isCloze) ClozeText.filled(prompt) else answer,
            source = sourceQuote?.takeIf { !isCloze },
            bookId = bookId,
            fromChapter = true,
        )
    }

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
    private suspend fun gradeChapterCard(
        id: String,
        rating: LumenRating,
        now: Instant,
        onUndo: (suspend () -> Unit) -> Unit,
    ): Instant? {
        val existing = learningCards.get(id) ?: return null
        val old = runCatching { FsrsCardCodec.fromJson(existing.fsrsCardJson) }.getOrNull() ?: return null
        val result = scheduler.reviewCard(old, rating.toFsrs(), now, null)
        val updated = result.card()
        val nextDue = updated.due ?: now.plusSeconds(86_400)

        // What was scheduled and what actually happened, captured BEFORE the
        // card is overwritten. A moment later both are gone: the previous due
        // date is the only thing that says whether this answer was on time,
        // and grading replaces it.
        val previousDue = existing.dueAt
        val scheduledDays = previousDue?.let { due ->
            java.time.Duration.between(
                java.time.Instant.ofEpochMilli(existing.updatedAt),
                java.time.Instant.ofEpochMilli(due),
            ).toDays()
        } ?: 0L
        val elapsedDays = java.time.Duration
            .between(java.time.Instant.ofEpochMilli(existing.updatedAt), now)
            .toDays()

        learningCards.upsert(
            existing.copy(
                fsrsCardJson = FsrsCardCodec.toJson(updated),
                dueAt = nextDue.toEpochMilli(),
                reviewCount = existing.reviewCount + 1,
                lastRating = rating.value,
                updatedAt = System.currentTimeMillis(),
            )
        )

        // Append-only, and never allowed to break the review. The scheduling
        // write above is what the reader is owed; the log is what lets the app
        // tell them later whether any of it worked.
        val logId = runCatching {
            reviewLog.insert(
                LearningReviewLogEntity(
                    cardId = id,
                    bookId = existing.bookId,
                    reviewedAt = now.toEpochMilli(),
                    rating = rating.value,
                    scheduledDays = scheduledDays,
                    elapsedDays = elapsedDays,
                    // An answer given before the card came due is not evidence
                    // of remembering anything, and the recall figure excludes
                    // it for that reason.
                    wasDue = previousDue != null && previousDue <= now.toEpochMilli(),
                )
            )
        }.getOrNull()

        onUndo {
            // The card exactly as it was, and the log row with it. Leaving the
            // row would count a mis-tap as a real answer forever, which is the
            // one thing a recall figure must not do.
            learningCards.upsert(existing)
            logId?.let { runCatching { reviewLog.deleteById(it) } }
        }
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
        val before = _state.value
        viewModelScope.launch {
            // The reader came back. The backoff ladder is about being ignored,
            // not about elapsed time, so answering anything resets it — and a
            // reader who has been ignoring reminders for a month is not left
            // permanently unreachable because of it.
            runCatching { settings.clearReminderStreak() }
            val now = Instant.now()
            var restore: (suspend () -> Unit)? = null
            val nextDue = runCatching {
                if (current.id in chapterCardIds) {
                    gradeChapterCard(current.id, rating, now) { restore = it }
                } else {
                    // Snapshot first: rateTraining overwrites the scheduler
                    // state in place and nothing else records what it was.
                    val snapshot = repository.trainingSnapshot(current.id)
                    val due = repository.rateTraining(current.id, rating, now)
                    if (snapshot != null) {
                        restore = { repository.restoreTraining(snapshot) }
                    }
                    due
                }
            }.getOrNull()
            val advanced = ReviewSession.grade(_state.value.session, failed = rating == LumenRating.AGAIN)
            undoStep = restore?.let {
                UndoStep(session = before.session, card = before.card, restore = it)
            }
            _state.value = _state.value.copy(
                session = advanced,
                card = advanced.current?.let { cards[it] },
                revealed = false,
                lastInterval = nextDue?.let { formatNextReview(it) },
                canUndo = undoStep != null,
            )
        }
    }

    /**
     * Takes back the last answer.
     *
     * Restores the card's scheduler state, removes its log row, and puts the
     * sitting back where it was — including the queue, because a failed card
     * was requeued three positions later and undoing the rating without
     * undoing that would leave a phantom repeat.
     *
     * The answer comes back revealed. The reader has already seen it; hiding
     * it again would ask them to pretend otherwise.
     */
    fun undo() {
        val step = undoStep ?: return
        undoStep = null
        viewModelScope.launch {
            runCatching { step.restore() }
            _state.value = _state.value.copy(
                session = step.session,
                card = step.card,
                revealed = true,
                lastInterval = null,
                canUndo = false,
            )
        }
    }

    /** Leaves the card for another day: no rating, so the scheduler is untouched. */
    fun skip() {
        // A skip changed nothing to take back, and a stale undo would restore
        // the session to a position two cards ago.
        undoStep = null
        val advanced = ReviewSession.skip(_state.value.session)
        _state.value = _state.value.copy(
            session = advanced,
            card = advanced.current?.let { cards[it] },
            revealed = false,
            lastInterval = null,
            canUndo = false,
        )
    }

}

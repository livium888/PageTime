package com.pagetime.app.ui.screens.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.LumenCardEntity
import com.pagetime.app.data.local.PagemarkEntity
import com.pagetime.app.data.review.ChapterCardGrader
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
    /** Why the answer is the answer, in the model's words. Null on older cards. */
    val explanation: String? = null,
    val source: String?,
    val bookId: String,
    /** Where the reader's own note came from, versus a generated question. */
    val fromChapter: Boolean,
    /**
     * A due reading chunk rather than a card.
     *
     * A chunk is not a question and is not graded here: the sitting's only
     * job is to remind the reader the passage is due and hand them to the
     * reader, where closing the chunk with a rating updates its schedule.
     */
    val isChunk: Boolean = false,
    /**
     * Which book and chapter this came from, for the line above the question.
     *
     * "From the book" was adequate when a chapter produced four cards. At
     * Quantum Country's density a sitting mixes fifty questions from several
     * books, and a prompt whose subject is ambiguous without knowing which
     * book asked it is unanswerable through no fault of the reader.
     *
     * Orbit solves the same problem by colouring each source; a title is
     * plainer and says more.
     */
    val sourceLabel: String? = null,
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
    /**
     * Anki-style next-interval captions per rating ("10m", "2d"), computed by
     * simulating the scheduler for the card on screen. Buttons show them so
     * the reader sees the cost of a rating before committing to it.
     */
    val intervalPreviews: Map<LumenRating, String> = emptyMap(),
    /** Browse seconds a correct answer banks; zero hides the note. */
    val rewardSeconds: Long = 0,
)

class ReviewSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repository = container.lumenRepository
    private val learningCards = container.database.learningCardDao()
    private val reviewLog = container.database.learningReviewLogDao()
    private val settings = container.settingsRepository
    private val bookDao = container.database.bookDao()
    private val pagemarks = container.pagemarkRepository
    private val balanceManager = container.balanceManager

    /**
     * Grading for chapter flashcards, shared with the reading chair.
     *
     * It builds its own scheduler, matching the one LumenRepository builds.
     * The important thing is that both card types, and both places a card can
     * be answered, go through the SAME FSRS configuration — two schedulers
     * with different parameters would give the same reader different intervals
     * depending on where they happened to answer.
     */
    private val grader = ChapterCardGrader(learningCards, reviewLog)

    private val _state = MutableStateFlow(ReviewUiState())
    val state = _state.asStateFlow()

    /** Cards held for the whole sitting; the session itself only carries ids. */
    private var cards: Map<String, ReviewItem> = emptyMap()

    /** Which table each id came from, so the right one is graded. */
    private var chapterCardIds: Set<String> = emptySet()

    /** Chunk ids, which are handed to the reader rather than graded. */
    private var chunkIds: Set<String> = emptySet()

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

            // Due chunks join the sitting between chapter cards and slip box
            // notes: re-reading is heavier than a flashcard but the memory
            // loop is the same, and neither should have to wait for the other.
            val chunks = runCatching {
                pagemarks.dueChunks(threshold)
            }.getOrDefault(emptyList())

            val titles = runCatching {
                bookDao.getAll().associate { it.id to it.title }
            }.getOrDefault(emptyMap())

            chapterCardIds = chapter.map { it.id }.toSet()
            chunkIds = chunks.map { it.id }.toSet()
            cards = (
                chapter.map { it.asReviewItem(titles[it.bookId]) } +
                    chunks.map { it.asReviewItem(titles[it.bookId]) } +
                    slips.map { it.asReviewItem(titles[it.bookId]) }
                ).associateBy { it.id }

            val session = ReviewSession.start(
                chapter.map { it.id } + chunks.map { it.id } + slips.map { it.id }
            )
            _state.value = ReviewUiState(
                loading = false,
                session = session,
                card = session.current?.let { cards[it] },
                revealed = false,
                rewardSeconds = runCatching { balanceManager.flashcardReward() }.getOrDefault(0L),
            )
            refreshPreviews()
        }
    }

    private fun LearningCardEntity.asReviewItem(bookTitle: String?): ReviewItem {
        // A cloze is shown as its sentence with a gap, and revealed as the same
        // sentence whole — never as the stored {{c1::…}} markup.
        val isCloze = cardType == LearningCardEntity.TYPE_CLOZE
        return ReviewItem(
            id = id,
            front = if (isCloze) ClozeText.blanked(prompt) else prompt,
            back = if (isCloze) ClozeText.filled(prompt) else answer,
            explanation = explanation?.takeIf { it.isNotBlank() },
            source = sourceQuote?.takeIf { !isCloze },
            bookId = bookId,
            fromChapter = true,
            sourceLabel = listOfNotNull(
                bookTitle,
                chapterTitle?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() },
        )
    }

    private fun PagemarkEntity.asReviewItem(bookTitle: String?): ReviewItem = ReviewItem(
        id = id,
        front = title,
        back = "",
        source = null,
        bookId = bookId,
        fromChapter = false,
        sourceLabel = bookTitle,
        isChunk = true,
    )

    private fun LumenCardEntity.asReviewItem(bookTitle: String?): ReviewItem {
        val (front, back) = repository.trainingPrompt(this)
        return ReviewItem(
            id = id,
            front = front,
            back = back,
            source = quote.takeIf { it.isNotBlank() && it != back },
            bookId = bookId,
            fromChapter = false,
            sourceLabel = bookTitle,
        )
    }

    /**
     * Applies a rating to a chapter flashcard.
     *
     * The work moved to [ChapterCardGrader] when the reading chair started
     * grading cards too. This is the sitting's half of it: the same scheduler,
     * and no status write, because a card in a sitting was accepted long ago.
     */
    private suspend fun gradeChapterCard(
        id: String,
        rating: LumenRating,
        now: Instant,
        onUndo: (suspend () -> Unit) -> Unit,
    ): Instant? {
        val graded = grader.grade(id, rating, now, keepIfUnjudged = false) ?: return null
        onUndo(graded.restore)
        return graded.nextDue
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
        // A chunk is never graded from the sitting — its rating belongs to the
        // reader's close-chunk flow, which is where the passage is fresh.
        if (current.isChunk) return
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
            // Correct recall banks the configured bonus. AGAIN proves nothing
            // and earns nothing — guessing can never mint app time.
            if (rating != LumenRating.AGAIN) {
                runCatching { balanceManager.earnFromFlashcard(ratingCorrect = true) }
            }
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
            refreshPreviews()
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

    /**
     * Hands a due chunk to the reader.
     *
     * The chunk is re-opened (READING, aimed at its start) and the caller
     * navigates only once that write has landed — [onOpened] fires after
     * [PagemarkRepository.resumeChunk] returns, so the reader cannot load
     * before the pending source exists and open at the wrong place.
     */
    fun readChunk(onOpened: () -> Unit = {}) {
        val current = _state.value.card ?: return
        if (!current.isChunk) return
        viewModelScope.launch {
            runCatching { pagemarks.resumeChunk(current.id) }
            onOpened()
        }
    }

    /**
     * Recomputes the per-button interval captions for the card now on screen.
     * A failed lookup simply leaves the map empty and the buttons captionless.
     */
    private fun refreshPreviews() {
        val item = _state.value.card ?: run {
            _state.value = _state.value.copy(intervalPreviews = emptyMap())
            return
        }
        if (item.isChunk) {
            _state.value = _state.value.copy(intervalPreviews = emptyMap())
            return
        }
        viewModelScope.launch {
            val now = Instant.now()
            val json: String? = runCatching {
                if (item.id in chapterCardIds) {
                    learningCards.get(item.id)?.fsrsCardJson
                } else {
                    repository.trainingSnapshot(item.id)?.fsrsCardJson
                }
            }.getOrNull()
            if (json == null) {
                _state.value = _state.value.copy(intervalPreviews = emptyMap())
                return@launch
            }
            val previews = LumenRating.entries.mapNotNull { rating ->
                grader.previewNextDue(json, rating, now)
                    ?.let { due -> rating to formatIntervalShort(due, now) }
            }.toMap()
            _state.value = _state.value.copy(intervalPreviews = previews)
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

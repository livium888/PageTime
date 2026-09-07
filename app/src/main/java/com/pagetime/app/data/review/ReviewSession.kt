package com.pagetime.app.data.review

/**
 * What is left to answer, and what has already been answered, in one sitting.
 *
 * Kept apart from the database and the screen so the awkward part — what
 * happens to a card you got wrong — can be tested without a phone.
 */
data class ReviewSessionState(
    /** Card ids still to answer, in the order they will be shown. */
    val queue: List<String>,
    /** Ids answered without a lapse this sitting; they will not come back. */
    val graduated: Set<String> = emptySet(),
    /** Total gradings, including repeats of a card that came back. */
    val answered: Int = 0,
    /** How many times a card was failed this sitting. */
    val lapses: Int = 0,
    /** Ids the sitting started with, for showing honest progress. */
    val started: Int = queue.size,
) {
    val current: String? get() = queue.firstOrNull()
    val finished: Boolean get() = queue.isEmpty()

    /**
     * Progress counts CARDS, not answers.
     *
     * A reader who fails one card six times has not done six cards' worth of
     * work, and a bar that says so is lying in the direction that feels worst.
     */
    val progress: Float
        get() = if (started <= 0) 1f else (graduated.size.toFloat() / started).coerceIn(0f, 1f)
}

/**
 * The rules of one review sitting.
 *
 * A CARD YOU GOT WRONG COMES BACK
 *
 * This is the difference between a review session and a quiz. Failing a card
 * and moving on teaches nothing: the whole value of retrieval practice is the
 * successful retrieval, and a card you never retrieved is a card you have only
 * been told the answer to. So a failed card returns later in the same sitting,
 * and the sitting is not over until you have got it right.
 *
 * It returns a few cards later rather than immediately. Immediately is not
 * retrieval — the answer is still on screen a second ago, so you are reading it
 * back rather than remembering it, and the card graduates while knowing
 * nothing. A short gap is enough to make it a real attempt.
 *
 * The scheduler and this are separate jobs and must not be confused. FSRS
 * decides when a card comes back on a LATER DAY, and it is told about every
 * grading including the failures. This decides only what happens for the next
 * few minutes.
 */
object ReviewSession {

    /**
     * How many other cards a failed card waits behind.
     *
     * Small enough that the sitting does not drag, large enough that the answer
     * is out of mind. Three is a guess informed by nothing but the reason
     * above; with fewer cards left than this, the card simply goes last.
     */
    const val AGAIN_GAP = 3

    /**
     * Cards in one sitting.
     *
     * A cap exists because an unbounded queue after a fortnight away is how
     * people quit spaced repetition — the backlog stops being a task and
     * becomes a verdict. The rest stay due and come back tomorrow.
     */
    const val MAX_SESSION = 50

    /**
     * How far ahead of now to consider a card due.
     *
     * A card falling due in six hours, when the reader is here now, should be
     * answered now: making them come back this evening for one card is worse
     * for memory and much worse for the habit. Sixteen hours covers "later
     * today" without dragging tomorrow's work forward.
     */
    const val LOOKAHEAD_MILLIS = 16L * 60L * 60L * 1000L

    fun dueThreshold(nowMillis: Long): Long = nowMillis + LOOKAHEAD_MILLIS

    fun start(cardIds: List<String>, limit: Int = MAX_SESSION): ReviewSessionState {
        val queue = cardIds.distinct().take(limit.coerceAtLeast(0))
        return ReviewSessionState(queue = queue, started = queue.size)
    }

    /**
     * Records an answer to the current card and returns the next state.
     *
     * [failed] is the reader saying they could not recall it — FSRS's "Again".
     * Anything else graduates the card for this sitting; the scheduler decides
     * how much credit the rating is worth on later days.
     */
    fun grade(state: ReviewSessionState, failed: Boolean): ReviewSessionState {
        val card = state.current ?: return state
        val rest = state.queue.drop(1)

        if (!failed) {
            return state.copy(
                queue = rest,
                graduated = state.graduated + card,
                answered = state.answered + 1,
            )
        }

        // Back into the queue, a few cards along. A card that failed and then
        // succeeds still counts as graduated — the reader did retrieve it —
        // but the lapse is recorded, and FSRS was told separately.
        val insertAt = minOf(AGAIN_GAP, rest.size)
        val requeued = rest.toMutableList().apply { add(insertAt, card) }
        return state.copy(
            queue = requeued,
            answered = state.answered + 1,
            lapses = state.lapses + 1,
        )
    }

    /** Leaves the current card for another day without answering it. */
    fun skip(state: ReviewSessionState): ReviewSessionState {
        if (state.current == null) return state
        return state.copy(queue = state.queue.drop(1), started = (state.started - 1).coerceAtLeast(0))
    }
}

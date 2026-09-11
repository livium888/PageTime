package com.pagetime.app.data

import com.pagetime.app.data.local.PagemarkEntity
import io.github.openspacedrepetition.Rating

/**
 * The rules of incremental reading, as pure functions.
 *
 * Kept apart from the database and the screen so the awkward parts — when a
 * chunk is due, what ordering the queue owes the reader, and what a rating
 * means — can be tested without a phone. This is the same split ReviewSession
 * and FirstReview use for review sessions.
 *
 * THE MODEL
 *
 * A book is read in chunks (pagemarks). At any moment the reader is inside at
 * most one chunk per book; everything else sits in a queue ordered by
 * priority. Finishing a chunk is a REVIEW, not a checkout: the reader says
 * how it went (Again / Hard / Good — never Easy, for the same reason FirstReview
 * withholds it), and FSRS decides when that chunk is worth re-reading. When a
 * chunk falls due it returns to the top of the queue, and the loop starts
 * again with the same span of text. That second, third and fourth pass over
 * the same chunk is what makes incremental reading different from just
 * bookmarking where you stopped.
 */
object PagemarkSession {

    const val MIN_PRIORITY = 1
    const val MAX_PRIORITY = 5
    const val DEFAULT_PRIORITY = 3

    enum class State {
        QUEUED, READING, SUSPENDED, DONE
    }

    fun clampPriority(priority: Int): Int =
        priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY)

    fun stateOf(entity: PagemarkEntity): State =
        runCatching { State.valueOf(entity.state) }.getOrDefault(State.QUEUED)

    // region Ratings

    /** FSRS ratings, stored in `lastRating` — the same numbers as FirstReview. */
    const val AGAIN = 1
    const val HARD = 2
    const val GOOD = 3

    /**
     * What the reader may say when closing a chunk.
     *
     * The same three the reading chair offers for a fresh flashcard, and for
     * the same reason: the passage was on screen seconds ago, so Easy would
     * report eyesight rather than memory and buy the chunk a false holiday.
     */
    val OFFERED_RATINGS: List<Int> = listOf(AGAIN, HARD, GOOD)

    /** Maps a stored rating to the FSRS scheduler's rating. */
    fun ratingToFsrs(rating: Int): Rating = when (rating) {
        AGAIN -> Rating.AGAIN
        HARD -> Rating.HARD
        GOOD -> Rating.GOOD
        else -> Rating.GOOD
    }

    // endregion

    // region Transitions

    /**
     * Opens a chunk for reading, from anywhere.
     *
     * A chunk that fell due is opened the same way a fresh one is; its FSRS
     * state stays on the row and is only updated when it is closed again.
     */
    fun begin(chunk: PagemarkEntity, now: Long = System.currentTimeMillis()): PagemarkEntity =
        chunk.copy(
            state = State.READING.name,
            // The re-read is happening NOW, so the chunk is no longer due. Left
            // on the row it would be both READing and due at once, and the
            // queue would draw it twice. Closing the chunk sets a fresh time.
            dueAt = null,
            updatedAt = now
        )

    /**
     * Pauses a chunk without judging it.
     *
     * The reader meant to come back; nothing is scheduled, the priority is
     * untouched, and the chunk just waits its turn in the queue again.
     * Only meaningful for a READING chunk; anything else is returned as-is.
     */
    fun suspend(chunk: PagemarkEntity, now: Long = System.currentTimeMillis()): PagemarkEntity =
        if (stateOf(chunk) == State.READING) {
            chunk.copy(
                state = State.SUSPENDED.name,
                dueAt = null,
                updatedAt = now
            )
        } else {
            chunk
        }

    /**
     * Closes a chunk at the position it reached, and turns the rating into
     * the next re-read time.
     *
     * [nextDueMillis] comes from the FSRS scheduler (the repository's job);
     * what belongs here is the rule that a chunk which gets a rating is DONE
     * with a due time, and that closing with AGAIN must schedule a far closer
     * re-read than closing with GOOD.
     */
    fun close(
        chunk: PagemarkEntity,
        rating: Int,
        endLocatorJson: String?,
        endFraction: Float,
        nextDueMillis: Long,
        now: Long = System.currentTimeMillis()
    ): PagemarkEntity = chunk.copy(
        state = State.DONE.name,
        endLocatorJson = endLocatorJson,
        endFraction = endFraction,
        dueAt = nextDueMillis,
        reviewCount = chunk.reviewCount + 1,
        lastRating = rating,
        updatedAt = now
    )

    // endregion

    // region Queue ordering

    /**
     * Whether a chunk is worth opening right now.
     *
     * READING is trivially "the chunk I am in". DONE chunks come back only
     * when their FSRS due time has arrived — the whole point of scheduling
     * the re-read rather than offering every finished chunk forever.
     */
    fun isActionable(chunk: PagemarkEntity, nowMillis: Long): Boolean = when (stateOf(chunk)) {
        State.READING, State.QUEUED, State.SUSPENDED -> true
        State.DONE -> chunk.dueAt != null && chunk.dueAt <= nowMillis
    }

    /**
     * The order the reading queue owes the reader.
     *
     * READING chunks first (the reader is mid-chunk; it belongs at the top).
     * Then anything due for re-reading, by how overdue it is. Then everything
     * not yet finished, higher priority first, oldest first inside a priority.
     * Finished chunks that are not due yet come last, by when they are due —
     * they are scheduled, not lost.
     */
    fun orderForQueue(items: List<PagemarkEntity>, nowMillis: Long): List<PagemarkEntity> {
        val reading = items.filter { stateOf(it) == State.READING }
        val due = items.filter {
            stateOf(it) == State.DONE && it.dueAt != null && it.dueAt <= nowMillis
        }.sortedBy { it.dueAt }
        val waiting = items.filter {
            stateOf(it) == State.QUEUED || stateOf(it) == State.SUSPENDED
        }.sortedWith(
            compareByDescending<PagemarkEntity> { it.priority }
                .thenBy { it.createdAt }
        )
        val scheduled = items.filter { stateOf(it) == State.DONE && (it.dueAt == null || it.dueAt > nowMillis) }
            .sortedBy { it.dueAt ?: Long.MAX_VALUE }
        return reading + due + waiting + scheduled
    }

    /** Due counts count ACTIONABLE chunks only: a chunk in mid-read is not "due". */
    fun dueCount(items: List<PagemarkEntity>, nowMillis: Long): Int =
        items.count { it.dueAt != null && it.dueAt <= nowMillis && stateOf(it) != State.READING }

    // endregion

    /**
     * The next auto-generated title for a book: "Chunk 4" when three chunks
     * already exist. The reader can rename it in the queue.
     */
    fun nextTitle(existingCount: Int): String = "Chunk ${existingCount + 1}"
}
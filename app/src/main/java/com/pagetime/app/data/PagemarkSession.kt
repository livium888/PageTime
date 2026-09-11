package com.pagetime.app.data

import com.pagetime.app.data.local.PagemarkEntity
import io.github.openspacedrepetition.Rating
import java.util.Locale
import kotlin.math.roundToInt

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
        QUEUED, READING, SUSPENDED, DONE, HARVESTED
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
     *
     * A HARVESTED chunk is the one exception, and it is returned untouched: a
     * chunk the reader has retired has said it has nothing left to give, and
     * quietly reopening it would be the app overruling them. Retirement is a
     * decision, not a pause.
     */
    fun begin(chunk: PagemarkEntity, now: Long = System.currentTimeMillis()): PagemarkEntity {
        if (stateOf(chunk) == State.HARVESTED) return chunk
        return chunk.copy(
            state = State.READING.name,
            // The re-read is happening NOW, so the chunk is no longer due. Left
            // on the row it would be both READing and due at once, and the
            // queue would draw it twice. Closing the chunk sets a fresh time.
            dueAt = null,
            updatedAt = now
        )
    }

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

    /**
     * Retires a chunk: it has given what it had, and it does not come back.
     *
     * WHY A CHUNK NEEDS AN END
     *
     * Closing a chunk schedules it, and scheduling is a promise to read it
     * again. Nothing in the loop could ever say "there is nothing left here" —
     * so a chunk re-read well, re-read for the fourth time, re-read past the
     * point of being able to say why, still came back on the FSRS calendar
     * forever. That is a treadmill: the passage never becomes knowledge and
     * never stops being work.
     *
     * Incremental reading has an end for every article, and it is this one: the
     * material's purpose is fulfilled once what mattered in it has been turned
     * into something recallable, at which point the passage is finished with.
     * Retirement is that act, and the knowledge the reader kept lives on in the
     * questions they made, not in the paragraph.
     *
     * The due time is cleared rather than kept: a retired chunk is not "due on
     * Tuesday", it is not due. Its span, its history and its rating survive on
     * the row, so retiring is a decision the reader can see, not a deletion.
     */
    fun harvest(chunk: PagemarkEntity, now: Long = System.currentTimeMillis()): PagemarkEntity =
        chunk.copy(
            state = State.HARVESTED.name,
            dueAt = null,
            updatedAt = now
        )

    /**
     * Whether the chunk in hand is a re-read rather than a first pass.
     *
     * The two passes are not the same job. A first pass is for reading; a
     * re-read is for taking something out of it — a question worth keeping — or
     * for deciding there is nothing left. Saying the same sentence on the chunk
     * bar for both was the app asking the reader to do the same thing twice and
     * calling it incremental reading.
     *
     * [PagemarkEntity.reviewCount] is the honest signal: it is only ever
     * incremented by closing a chunk with a rating, so a chunk that has been
     * closed once has been read once.
     */
    fun isReRead(chunk: PagemarkEntity): Boolean = chunk.reviewCount > 0

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
        // Retired on purpose, and the whole point of retiring it is that it
        // stops asking to be read. It stays in the list so the reader can see
        // what they have finished with, and it is never drawn as due again.
        State.HARVESTED -> false
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
        // Retired chunks come last, most recently retired first: they are the
        // record of what the reader has finished with, not a queue. Putting
        // them anywhere else would make a done chunk look like work waiting.
        val retired = items.filter { stateOf(it) == State.HARVESTED }
            .sortedByDescending { it.updatedAt }
        return reading + due + waiting + scheduled + retired
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

    // region One action, not two

    /**
     * Close enough to the end of the book that there is nothing left to chunk.
     */
    const val END_OF_BOOK = 0.995f

    /**
     * Whether finishing a chunk at [endFraction] should open the next one.
     *
     * WHY FINISHING IS ONE ACTION AND NOT TWO
     *
     * The reader used to have to tap "Start chunk here" before reading and
     * "Close chunk" when they stopped — two taps for one idea, and the first
     * one had to happen at exactly the right moment or the chunk silently
     * covered the wrong span. But a chunk's end IS the next chunk's start:
     * the reader stops where they stop, and the next sitting begins there.
     * So there is only ever one action — "finish this chunk" — and it both
     * closes the span that was read and opens the span that is next.
     *
     * The exception is the end of the book, where opening a chunk on the last
     * page would leave a ghost in the queue that can never be read.
     */
    fun opensNextChunk(endFraction: Float): Boolean = endFraction < END_OF_BOOK

    /**
     * Whether a span of the book is inside a chunk.
     *
     * The one thing incremental reading has to be able to show and could not:
     * where the chunk starts and where it ends. A plain-text page knows the
     * span of the book it covers, so the two can be compared — and the page
     * the reader is on is inside the chunk exactly when the spans overlap.
     *
     * The end passed in for a chunk still being read is the reader's own
     * position, not the 0 the row holds: the span is open until they say where
     * it stops.
     */
    fun coversSpan(
        startFraction: Float,
        endFraction: Float,
        spanStartFraction: Float,
        spanEndFraction: Float
    ): Boolean =
        spanEndFraction > startFraction && spanStartFraction < endFraction

    /**
     * The span of a chunk in words, because "start and end" is otherwise
     * nowhere on the screen: "34% → 41%". A chunk that has not been finished
     * has no end yet, so it reads "from 34%".
     */
    fun spanLabel(startFraction: Float, endFraction: Float): String {
        val from = percent(startFraction)
        val to = percent(endFraction)
        return if (endFraction <= startFraction) "from $from" else "$from \u2192 $to"
    }

    /**
     * When a chunk comes back, said as a time rather than as a state name.
     *
     * "Scheduled" tells the reader nothing they can act on; the whole point
     * of the schedule is that the passage returns on a particular day.
     */
    fun dueLabel(dueAtMillis: Long?, nowMillis: Long): String {
        if (dueAtMillis == null) return "Not scheduled"
        val remaining = dueAtMillis - nowMillis
        if (remaining <= 0L) return "Due now"
        val minutes = remaining / 60_000L
        return when {
            // Hours rather than "tomorrow", because tomorrow is a claim about
            // the calendar and this only knows the distance.
            minutes < 60L -> "Back in ${minutes.coerceAtLeast(1L)} min"
            minutes < 2_880L -> "Back in ${(minutes + 30L) / 60L} h"
            else -> "Back in ${(minutes + 720L) / 1_440L} days"
        }
    }

    private fun percent(fraction: Float): String =
        String.format(Locale.US, "%d%%", (fraction.coerceIn(0f, 1f) * 100f).roundToInt())

    // endregion
}
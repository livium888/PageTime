package com.pagetime.app.data.review

import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.LearningReviewLogEntity
import com.pagetime.app.data.local.LearningReviewLogDao
import io.github.openspacedrepetition.Scheduler
import java.time.Duration
import java.time.Instant

/**
 * Applying a rating to a chapter flashcard, from wherever the reader answered
 * it.
 *
 * WHY THIS IS NOT IN THE REVIEW SCREEN ANY MORE
 *
 * It used to be a private method on ReviewSessionViewModel, which was fine
 * while the review screen was the only place a card could be answered. It no
 * longer is: a question surfaced mid-chapter is now graded in the reading
 * chair. Two copies of this would be two schedulers, two log formats, and
 * eventually two different answers to "when does this card come back" — so
 * there is one copy and both callers use it.
 *
 * THE SCHEDULER IS SHARED ON PURPOSE
 *
 * FSRS parameters are per-collection, not per-screen. A card rated Good in the
 * reader and a card rated Good in a sitting must get the same interval, or the
 * reader's memory model is being fitted to two different curves at once.
 */
class ChapterCardGrader(
    private val cards: LearningCardDao,
    private val reviewLog: LearningReviewLogDao,
    private val scheduler: Scheduler = Scheduler.builder().build(),
) {

    /**
     * What a grading did, and how to take it back.
     *
     * [restore] is handed out rather than applied, because whether an answer
     * can be undone is the caller's question: the review sitting keeps one
     * step, the reading chair keeps none.
     */
    data class Graded(
        val nextDue: Instant,
        val restore: suspend () -> Unit,
    )

    /**
     * Records an answer.
     *
     * Two writes, the same two LumenRepository.rateTraining does: the
     * scheduler's new state as JSON, and dueAt lifted out of it so the due
     * query stays a WHERE clause.
     *
     * [keepIfUnjudged] is what makes an answer in the reading chair an
     * acceptance. A prompt is written to the table as PENDING before anyone
     * has seen it — generating a chapter costs an API call and losing it would
     * mean paying twice — and answering the question is the reader saying yes.
     * The review sitting passes false: those cards were accepted long ago and
     * a status write there would be a lie about when.
     */
    suspend fun grade(
        id: String,
        rating: LumenRating,
        now: Instant,
        keepIfUnjudged: Boolean = false,
    ): Graded? {
        val existing = cards.get(id) ?: return null
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
            Duration.between(Instant.ofEpochMilli(existing.updatedAt), Instant.ofEpochMilli(due)).toDays()
        } ?: 0L
        val elapsedDays = Duration
            .between(Instant.ofEpochMilli(existing.updatedAt), now)
            .toDays()

        val status = if (keepIfUnjudged && existing.status == LearningCardEntity.STATUS_PENDING) {
            LearningCardEntity.STATUS_KEPT
        } else {
            existing.status
        }

        cards.upsert(
            existing.copy(
                fsrsCardJson = FsrsCardCodec.toJson(updated),
                dueAt = nextDue.toEpochMilli(),
                reviewCount = existing.reviewCount + 1,
                lastRating = rating.value,
                status = status,
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
                    // it for that reason. A first sighting in the reading
                    // chair has no due date at all, so it never counts.
                    wasDue = previousDue != null && previousDue <= now.toEpochMilli(),
                )
            )
        }.getOrNull()

        return Graded(
            nextDue = nextDue,
            restore = {
                // The card exactly as it was, and the log row with it. Leaving
                // the row would count a mis-tap as a real answer forever,
                // which is the one thing a recall figure must not do.
                cards.upsert(existing)
                logId?.let { runCatching { reviewLog.deleteById(it) } }
            },
        )
    }
}

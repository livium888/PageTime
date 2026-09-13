package com.pagetime.app.data.review

import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LumenCardDao
import com.pagetime.app.data.local.PagemarkDao
import com.pagetime.app.data.local.SettingsRepository
import io.github.openspacedrepetition.State
import java.time.Duration
import java.time.Instant

/**
 * Moves cards that were already scheduled onto the schedule the reader now
 * wants.
 *
 * WHY THIS HAS TO BE A BUTTON
 *
 * "Deck options are not retroactive" is a sentence in Anki's own manual, and it
 * is true here for the same reason: a card's due date is stored, not derived.
 * Change the desired retention from 90% to 85% and every existing interval was
 * computed under the old number and stays exactly where it was — the setting is
 * correct and nothing the reader can see changes. This is the action that
 * re-derives them.
 *
 * WHAT IT DOES NOT TOUCH
 *
 * A card still inside a learning step is left alone. It has a schedule that is
 * minutes old and about to resolve itself the moment the reader answers it, and
 * moving those cards to a day-scale interval would be throwing away the step
 * they are in the middle of. So the count that comes back has three parts —
 * moved, already right, and left alone — and the screen shows all three, because
 * "rescheduled 0 cards" and "rescheduled 0 cards because you have none" look
 * identical otherwise.
 *
 * [rescheduleAll] rewrites due dates in bulk, in one pass, with no undo. That is
 * the shape Anki's own Reschedule gives it too, and it is why it is a button the
 * reader has to press rather than something a settings change does quietly.
 */
class RescheduleRepository(
    private val learningCards: LearningCardDao,
    private val lumenCards: LumenCardDao,
    private val pagemarks: PagemarkDao,
    private val settings: SettingsRepository,
) {

    /**
     * [moved] cards whose due date changed, [alreadyRight] those the new policy
     * agreed with, and [leftLearning] those still mid-step.
     */
    data class Result(
        val moved: Int,
        val alreadyRight: Int,
        val leftLearning: Int,
    ) {
        val total: Int get() = moved + alreadyRight + leftLearning
    }

    suspend fun rescheduleAll(now: Instant = Instant.now()): Result {
        val policy = settings.currentSchedulingPolicy()
        var moved = 0
        var alreadyRight = 0
        var leftLearning = 0

        learningCards.allForReschedule().forEach { card ->
            when (val next = rescheduledDue(card.fsrsCardJson, policy, now)) {
                null -> leftLearning++
                else -> if (next.second == card.dueAt) {
                    alreadyRight++
                } else {
                    // updatedAt is deliberately untouched. The review log reads
                    // it as "when this card was last answered" to work out how
                    // late the next answer was, and moving a due date is not an
                    // answer — writing it here would make every card in the
                    // collection look like it had just been reviewed.
                    learningCards.upsert(card.copy(fsrsCardJson = next.first, dueAt = next.second))
                    moved++
                }
            }
        }

        lumenCards.allForReschedule().forEach { card ->
            when (val next = rescheduledDue(card.fsrsCardJson, policy, now)) {
                null -> leftLearning++
                else -> if (next.second == card.dueAt) {
                    alreadyRight++
                } else {
                    lumenCards.upsert(card.copy(fsrsCardJson = next.first, dueAt = next.second))
                    moved++
                }
            }
        }

        pagemarks.allForReschedule().forEach { card ->
            when (val next = rescheduledDue(card.fsrsCardJson, policy, now)) {
                null -> leftLearning++
                else -> if (next.second == card.dueAt) {
                    alreadyRight++
                } else {
                    pagemarks.upsert(card.copy(fsrsCardJson = next.first, dueAt = next.second))
                    moved++
                }
            }
        }

        return Result(moved = moved, alreadyRight = alreadyRight, leftLearning = leftLearning)
    }

}

/**
 * The card's new schedule, or null when it has none to move.
 *
 * A top-level function rather than a method so it can be tested without three
 * Room DAOs standing behind it — the arithmetic is the part worth pinning, and
 * the DAO loop is the part that is only a `forEach`.
 *
 * The new due date is anchored to the card's own last review rather than to
 * now, which is what makes this a reschedule and not a reset: a card last
 * answered four days ago with a thirty-day interval stays twenty-six days away
 * instead of jumping back to thirty from today.
 */
internal fun rescheduledDue(
    fsrsCardJson: String?,
    policy: SchedulingPolicy,
    now: Instant,
): Pair<String, Long>? {
    val json = fsrsCardJson ?: return null
    val card = runCatching { FsrsCardCodec.fromJson(json) }.getOrNull() ?: return null
    // A card in a learning step is mid-answer. It will be scheduled properly the
    // moment the reader answers it; moving it now would throw the step away.
    if (card.state != State.REVIEW) return null
    // No stability means the card has never been reviewed in a way FSRS can
    // derive an interval from, so there is nothing to re-derive.
    val stability = card.stability ?: return null

    val anchor = card.lastReview ?: now
    val days = FsrsScheduling.intervalDaysFor(stability, policy).toLong()
    val due = anchor.plus(Duration.ofDays(days))
    card.due = due
    return FsrsCardCodec.toJson(card) to due.toEpochMilli()
}

package com.pagetime.app.data.review

import kotlin.math.pow

/**
 * One card's schedule, reduced to the two numbers a reminder decision needs.
 *
 * [intervalMillis] is how long the card was meant to survive — the gap the
 * scheduler chose — not how long it has been waiting. A card on a two-day
 * interval that is a day late is in real trouble; a card on a two-year
 * interval that is a day late is fine, and the difference is the whole point.
 */
data class DueCard(
    val dueAtMillis: Long,
    val intervalMillis: Long,
)

/** What the reminder decided, and why. Named so a decision can be logged. */
enum class ReminderVerdict {
    /** Enough is waiting that a sitting is worth having now. */
    FULL_SESSION,

    /** Waiting longer would cost real forgetting. */
    TOO_MUCH_DECAY,

    /** Nothing better is coming; this is as good a moment as any. */
    NO_BETTER_SOON,

    /** Nothing is due. */
    NOTHING_DUE,

    /** Something is due, but waiting a day or two gathers a fuller sitting. */
    FULLER_SESSION_SOON,

    /** The reader turned reminders off, or snoozed them. */
    SILENCED,

    /** Already reminded recently, or reminded enough times without an answer. */
    TOO_SOON;

    val shouldNotify: Boolean
        get() = this == FULL_SESSION || this == TOO_MUCH_DECAY || this == NO_BETTER_SOON
}

/**
 * When to interrupt someone about their flashcards.
 *
 * WHY THIS IS NOT "TELL THEM WHEN A CARD IS DUE"
 *
 * Because that produces a dribble of two-card pings, and a notification that
 * is usually not worth opening trains the reader to dismiss it — after which
 * the feature is worse than not having it, since it costs attention and
 * delivers nothing.
 *
 * This is ported from Orbit's backend, which faces exactly this problem and
 * solves it by asking a different question: not "is anything due?" but "would
 * waiting be cheaper than interrupting?".
 *
 * THE COST MODEL
 *
 * A card answered exactly when due is remembered with probability ~0.9, and
 * accuracy decays as it goes overdue relative to its own interval. Summing
 * (1 - accuracy) across everything waiting gives the expected number of cards
 * that will be forgotten because of the delay. Below two, waiting is nearly
 * free and worth doing to gather a fuller sitting; at two, the delay is
 * costing the reader real memories and it is time to speak up.
 *
 * The constants are Orbit's, kept rather than re-derived: they come from
 * running this system against real readers, which is evidence this app does
 * not have.
 *
 * WHAT IS DELIBERATELY NOT PORTED
 *
 * Orbit's scheduler. PageTime uses FSRS, which models difficulty and stability
 * per card instead of a fixed 2.3x growth factor, and is strictly better. This
 * file is about when to send the reminder, not when the card is due.
 */
object ReviewReminder {

    /**
     * Wait until the delay is costing this many cards.
     *
     * Orbit's number. Below it, a delay is cheap enough that gathering a
     * fuller session is the better trade.
     */
    const val FORGOTTEN_THRESHOLD = 2.0

    /** Probability a card is remembered when answered exactly on time. */
    const val ACCURACY_WHEN_DUE = 0.9

    /** How far ahead to look for a fuller sitting. */
    const val LOOKAHEAD_DAYS = 7

    const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /**
     * Cards falling due within the next sixteen hours count as due now.
     *
     * The same lookahead the review session itself uses: a card due this
     * evening should be answered while the reader is here, not tomorrow.
     */
    const val FUZZ_MILLIS = 16L * 60L * 60L * 1000L

    /**
     * How many days after an unanswered reminder to send the next one.
     *
     * Orbit's ladder, and the important part is where it ends. After six
     * unanswered reminders the app stops asking, permanently, until the reader
     * comes back on their own. An app that keeps nagging someone who has moved
     * on is not helping them learn; it is just a thing to turn off, and by
     * then it has usually taken the whole app's notifications with it.
     */
    private val BACKOFF_DAYS = listOf(2, 2, 5, 10, 20, 30)

    /**
     * Whether a repeat reminder is allowed yet.
     *
     * [remindersSent] counts consecutive reminders the reader has not acted
     * on; it resets when they review.
     */
    fun mayRemindAgain(
        remindersSent: Int,
        millisSinceLastReminder: Long,
    ): Boolean {
        if (remindersSent <= 0) return true
        val gap = BACKOFF_DAYS.getOrNull(remindersSent - 1) ?: return false
        return millisSinceLastReminder >= gap * DAY_MILLIS
    }

    /**
     * Expected number of cards that will be forgotten because of the delay.
     *
     * Overdue-ness is measured against each card's OWN interval, floored at a
     * day so a card on a ten-minute learning step does not read as
     * catastrophically overdue an hour later.
     */
    fun expectedForgotten(cards: List<DueCard>, nowMillis: Long): Double =
        cards.sumOf { card ->
            val scale = maxOf(DAY_MILLIS, card.intervalMillis).toDouble()
            val overdue = (nowMillis - card.dueAtMillis) / scale
            val accuracy = ACCURACY_WHEN_DUE.pow(overdue).coerceAtMost(1.0)
            1.0 - accuracy
        }

    /** Cards due at [atMillis], counting the sixteen-hour lookahead. */
    fun dueAt(cards: List<DueCard>, atMillis: Long): List<DueCard> =
        cards.filter { it.dueAtMillis <= atMillis + FUZZ_MILLIS }

    /**
     * The decision.
     *
     * [sessionCap] is the most cards a sitting will offer, so a queue already
     * past it cannot be improved by waiting.
     */
    fun decide(
        cards: List<DueCard>,
        nowMillis: Long,
        sessionCap: Int = ReviewSession.MAX_SESSION,
    ): ReminderVerdict {
        if (cards.isEmpty()) return ReminderVerdict.NOTHING_DUE

        val dueNow = dueAt(cards, nowMillis)
        if (dueNow.isEmpty()) return ReminderVerdict.NOTHING_DUE

        // A sitting is already as full as it can get; waiting adds nothing.
        if (dueNow.size >= minOf(cards.size, sessionCap)) return ReminderVerdict.FULL_SESSION

        // The delay has started costing real memories.
        if (expectedForgotten(dueNow, nowMillis) >= FORGOTTEN_THRESHOLD) {
            return ReminderVerdict.TOO_MUCH_DECAY
        }

        for (day in 0 until LOOKAHEAD_DAYS) {
            val future = nowMillis + day * DAY_MILLIS
            val futureDue = dueAt(cards, future)
            // Waiting this long would cost too much; stop looking.
            if (expectedForgotten(futureDue, future) >= FORGOTTEN_THRESHOLD) break
            // A fuller sitting is coming, cheaply. Say nothing today.
            if (futureDue.size > dueNow.size) return ReminderVerdict.FULLER_SESSION_SOON
        }

        return ReminderVerdict.NO_BETTER_SOON
    }
}

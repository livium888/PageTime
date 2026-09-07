package com.pagetime.app.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When to interrupt someone about their flashcards.
 *
 * The failure this guards against is not a crash. It is a notification that is
 * usually not worth opening, which trains the reader to dismiss it — after
 * which the feature costs attention and delivers nothing, and is worse than
 * never having existed.
 */
class ReviewReminderTest {

    private val day = ReviewReminder.DAY_MILLIS
    private val now = 1_000_000_000_000L

    private fun card(dueDaysAgo: Double, intervalDays: Long = 10) = DueCard(
        dueAtMillis = now - (dueDaysAgo * day).toLong(),
        intervalMillis = intervalDays * day,
    )

    @Test
    fun `nothing due says nothing`() {
        assertEquals(ReminderVerdict.NOTHING_DUE, ReviewReminder.decide(emptyList(), now))
        // Due in three days is not due, even with the sixteen-hour lookahead.
        val later = listOf(DueCard(now + 3 * day, 10 * day))
        assertEquals(ReminderVerdict.NOTHING_DUE, ReviewReminder.decide(later, now))
    }

    @Test
    fun `a card due this evening counts as due now`() {
        // The same sixteen-hour fuzz the review session uses. Somebody at
        // their phone now should answer tonight's card now.
        val tonight = listOf(DueCard(now + 8 * 60 * 60 * 1000L, 10 * day))
        assertEquals(1, ReviewReminder.dueAt(tonight, now).size)
    }

    @Test
    fun `one barely overdue card waits for company`() {
        // The whole reason this is not "notify when a card is due". One card,
        // an hour late, is not worth interrupting anyone for — especially when
        // more are coming in a couple of days.
        val cards = listOf(
            card(dueDaysAgo = 0.05),
            DueCard(now + 2 * day, 10 * day),
            DueCard(now + 2 * day, 10 * day),
        )
        assertEquals(ReminderVerdict.FULLER_SESSION_SOON, ReviewReminder.decide(cards, now))
    }

    @Test
    fun `waiting stops being free once it costs two cards`() {
        // Twenty cards, each a full interval overdue: accuracy has fallen to
        // 0.9 of itself for every one of them, so the expected loss is well
        // past two and the delay is now costing real memories.
        val cards = List(20) { card(dueDaysAgo = 10.0, intervalDays = 10) } +
            List(30) { DueCard(now + 1 * day, 10 * day) }
        val verdict = ReviewReminder.decide(cards, now)
        assertEquals(ReminderVerdict.TOO_MUCH_DECAY, verdict)
        assertTrue(verdict.shouldNotify)
    }

    @Test
    fun `a full sitting is never postponed`() {
        // Fifty due is the session cap: waiting cannot make it better.
        val cards = List(60) { card(dueDaysAgo = 0.1) }
        assertEquals(ReminderVerdict.FULL_SESSION, ReviewReminder.decide(cards, now))
    }

    @Test
    fun `nothing better coming means now is the moment`() {
        // Three due, and the rest not for a month — so the week-long lookahead
        // finds no fuller sitting, and the delay is costing almost nothing.
        // There is no reason to wait and no reason to hurry: go now.
        val cards = List(3) { card(dueDaysAgo = 1.0) } +
            List(4) { DueCard(now + 30 * day, 30 * day) }
        assertEquals(ReminderVerdict.NO_BETTER_SOON, ReviewReminder.decide(cards, now))
    }

    @Test
    fun `everything you have being due is a full sitting`() {
        // Not a special case worth postponing: if every card you own is due,
        // waiting cannot gather more of them.
        val cards = List(3) { card(dueDaysAgo = 1.0) }
        assertEquals(ReminderVerdict.FULL_SESSION, ReviewReminder.decide(cards, now))
    }

    @Test
    fun `overdue is measured against the card's own interval`() {
        // A day late on a two-day interval is trouble; a day late on a
        // two-year interval is nothing. A model that treated them alike would
        // notify constantly about cards that are perfectly safe.
        val fragile = ReviewReminder.expectedForgotten(
            listOf(card(dueDaysAgo = 1.0, intervalDays = 2)), now,
        )
        val durable = ReviewReminder.expectedForgotten(
            listOf(card(dueDaysAgo = 1.0, intervalDays = 700)), now,
        )
        assertTrue("fragile $fragile should exceed durable $durable", fragile > durable * 5)
    }

    @Test
    fun `a card answered on time costs nothing`() {
        val onTime = listOf(DueCard(now, 10 * day))
        assertEquals(0.0, ReviewReminder.expectedForgotten(onTime, now), 0.0001)
    }

    @Test
    fun `an early answer is not counted as negative forgetting`() {
        // Accuracy is capped at 1. Without the cap a pile of not-yet-due cards
        // would produce a negative expected loss and could cancel out real
        // overdue ones, silencing a reminder that was needed.
        val early = listOf(DueCard(now + 5 * day, 10 * day))
        assertEquals(0.0, ReviewReminder.expectedForgotten(early, now), 0.0001)
    }

    // The backoff ladder
    // ==================

    @Test
    fun `the first reminder needs no waiting`() {
        assertTrue(ReviewReminder.mayRemindAgain(remindersSent = 0, millisSinceLastReminder = 0))
    }

    @Test
    fun `reminders spread out as they go unanswered`() {
        // 2, 2, 5, 10, 20, 30 days — Orbit's ladder.
        assertFalse(ReviewReminder.mayRemindAgain(1, 1 * day))
        assertTrue(ReviewReminder.mayRemindAgain(1, 2 * day))
        assertFalse(ReviewReminder.mayRemindAgain(3, 4 * day))
        assertTrue(ReviewReminder.mayRemindAgain(3, 5 * day))
        assertFalse(ReviewReminder.mayRemindAgain(6, 29 * day))
        assertTrue(ReviewReminder.mayRemindAgain(6, 30 * day))
    }

    @Test
    fun `after six unanswered reminders the app stops asking`() {
        // Permanently, until the reader comes back on their own. An app that
        // keeps nagging someone who has moved on is not helping them learn;
        // it is a thing to be turned off, and it usually takes every other
        // notification the app sends with it.
        assertFalse(ReviewReminder.mayRemindAgain(7, 365 * day))
        assertFalse(ReviewReminder.mayRemindAgain(50, 3650 * day))
    }
}

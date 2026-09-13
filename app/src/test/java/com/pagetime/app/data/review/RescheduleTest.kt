package com.pagetime.app.data.review

import com.pagetime.app.data.FsrsCardCodec
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.State
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving cards that were already scheduled onto a new schedule.
 *
 * The arithmetic is what is worth pinning: which cards are skipped, and that the
 * new due date is measured from the card's own last review rather than from
 * today. Getting the anchor wrong turns "reschedule" into "reset" and would move
 * a card the reader is four days into a thirty-day interval back out to thirty
 * days, which is not what the button says it does.
 */
class RescheduleTest {

    private val now = Instant.parse("2026-04-01T09:00:00Z")

    private fun json(
        state: State = State.REVIEW,
        stability: Double? = 10.0,
        difficulty: Double? = 5.0,
        step: Int? = null,
        lastReview: Instant? = now.minus(Duration.ofDays(10)),
    ): String {
        val builder = Card.builder().cardId(1).state(state).due(now)
        if (step != null) builder.step(step)
        if (stability != null) builder.stability(stability)
        if (difficulty != null) builder.difficulty(difficulty)
        if (lastReview != null) builder.lastReview(lastReview)
        return FsrsCardCodec.toJson(builder.build())
    }

    @Test
    fun `a card with no schedule is left alone rather than invented`() {
        assertNull(rescheduledDue(null, SchedulingPolicy(), now))
        assertNull(rescheduledDue("not json at all", SchedulingPolicy(), now))
    }

    @Test
    fun `a card still in a learning step keeps the step it is in`() {
        // It is about to be answered, and answering it schedules it properly.
        // Moving it now would throw away the repeat it was deliberately given.
        assertNull(rescheduledDue(json(state = State.LEARNING, step = 0), SchedulingPolicy(), now))
    }

    @Test
    fun `a card with no stability has no interval to re-derive`() {
        assertNull(rescheduledDue(json(stability = null, difficulty = null), SchedulingPolicy(), now))
    }

    @Test
    fun `the new due date is measured from the card's last review, not from today`() {
        // Ten days into an interval and the reader raises the floor to thirty.
        // The card should be twenty days away, not thirty.
        val result = rescheduledDue(
            json(stability = 10.0, lastReview = now.minus(Duration.ofDays(10))),
            SchedulingPolicy(minimumIntervalDays = 30),
            now,
        )
        assertEquals(now.plus(Duration.ofDays(20)).toEpochMilli(), result!!.second)
    }

    @Test
    fun `a card with no last review is anchored to now rather than dropped`() {
        val result = rescheduledDue(
            json(stability = 10.0, lastReview = null),
            SchedulingPolicy(minimumIntervalDays = 30),
            now,
        )
        assertEquals(now.plus(Duration.ofDays(30)).toEpochMilli(), result!!.second)
    }

    @Test
    fun `wanting to remember more often moves a card closer`() {
        val relaxed = rescheduledDue(
            json(stability = 50.0, lastReview = now),
            SchedulingPolicy(desiredRetention = 0.75),
            now,
        )!!.second
        val strict = rescheduledDue(
            json(stability = 50.0, lastReview = now),
            SchedulingPolicy(desiredRetention = 0.95),
            now,
        )!!.second
        assertTrue("$relaxed should be later than $strict", relaxed > strict)
    }

    @Test
    fun `the rewritten card is still a parseable card with the new due date`() {
        // The JSON and the dueAt column are two stored copies of one fact and
        // they have to agree, or the reader sees one date in the queue and gets
        // another when the card is answered.
        val policy = SchedulingPolicy(minimumIntervalDays = 30)
        val (updatedJson, dueAt) = rescheduledDue(json(stability = 10.0), policy, now)!!

        val reparsed = FsrsCardCodec.fromJson(updatedJson)
        assertEquals(State.REVIEW, reparsed.state)
        assertEquals(dueAt, reparsed.due.toEpochMilli())
    }

    @Test
    fun `rescheduling twice is the same as rescheduling once`() {
        // The button is on a settings screen and can be pressed repeatedly; a
        // second press must not compound the first.
        val policy = SchedulingPolicy(minimumIntervalDays = 30)
        val first = rescheduledDue(json(stability = 10.0), policy, now)!!
        val second = rescheduledDue(first.first, policy, now)!!
        assertEquals(first.second, second.second)
    }
}

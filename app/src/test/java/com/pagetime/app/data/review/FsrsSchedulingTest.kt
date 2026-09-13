package com.pagetime.app.data.review

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.State
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app actually asks FSRS to do.
 *
 * These are the tests that would have caught the bug the reader reported: a
 * card answered Good on a new card coming back in ten minutes. They also pin
 * the two things that are easy to get subtly wrong and impossible to notice —
 * that the interval on a rating button is the interval that rating produces,
 * and that the one formula this app re-implements agrees with the library's.
 */
class FsrsSchedulingTest {

    private val now = Instant.parse("2026-03-01T09:00:00Z")

    private fun newCard(id: Int = 1) = Card.builder().cardId(id).due(now).build()

    /** A graduated card, mid-life, with a stability FSRS can derive an interval from. */
    private fun reviewCard(
        id: Int = 2,
        stability: Double = 10.0,
        difficulty: Double = 5.0,
        lastReview: Instant = now.minus(Duration.ofDays(10)),
    ) = Card.builder()
        .cardId(id)
        .state(State.REVIEW)
        .stability(stability)
        .difficulty(difficulty)
        .due(now)
        .lastReview(lastReview)
        .build()

    private fun dueDays(result: io.github.openspacedrepetition.CardAndReviewLog): Long =
        ChronoUnit.DAYS.between(now, result.card().due)

    // ---- The steps, which are the whole complaint -------------------------

    @Test
    fun `Good on a new card graduates it to days, not to ten minutes`() {
        val result = FsrsScheduling.review(
            policy = SchedulingPolicy(),
            card = newCard(),
            rating = Rating.GOOD,
            now = now,
            seed = FsrsScheduling.seed("card-1", Rating.GOOD.value),
        )

        assertEquals(State.REVIEW, result.card().state)
        assertTrue(
            "Good must hand the card to FSRS, not bring it back in minutes",
            result.card().due.isAfter(now.plus(Duration.ofHours(20))),
        )
    }

    @Test
    fun `Good on the last step of the old two step schedule graduates the card`() {
        // Every card already in the collection was scheduled under the library's
        // default `1m 10m` steps. One of them can be sitting on step 1, and the
        // policy now has a single step, so step 1 is past the end of the list —
        // which is exactly the edge case the library's own comment describes.
        val halfLearned = Card.builder()
            .cardId(3)
            .state(State.LEARNING)
            .step(1)
            .stability(1.0)
            .difficulty(5.0)
            .due(now)
            .lastReview(now.minus(Duration.ofMinutes(10)))
            .build()

        val result = FsrsScheduling.review(
            policy = SchedulingPolicy(),
            card = halfLearned,
            rating = Rating.GOOD,
            now = now,
            seed = FsrsScheduling.seed("card-3", Rating.GOOD.value),
        )

        assertEquals(State.REVIEW, result.card().state)
        assertTrue(result.card().due.isAfter(now.plus(Duration.ofDays(1))))
    }

    @Test
    fun `Again and Hard still bring a new card back while the reader is sitting there`() {
        // Otherwise the four buttons would be four ways of saying the same thing.
        for (rating in listOf(Rating.AGAIN, Rating.HARD)) {
            val result = FsrsScheduling.review(
                policy = SchedulingPolicy(),
                card = newCard(),
                rating = rating,
                now = now,
                seed = FsrsScheduling.seed("card-1", rating.value),
            )
            val minutes = ChronoUnit.MINUTES.between(now, result.card().due)
            assertEquals("$rating should stay in learning", State.LEARNING, result.card().state)
            assertTrue("$rating came back in $minutes minutes", minutes >= 1L && minutes <= 60L)
        }
    }

    @Test
    fun `Hard on a single step waits longer than Again`() {
        val again = FsrsScheduling.review(
            SchedulingPolicy(), newCard(), Rating.AGAIN, now, FsrsScheduling.seed("c", Rating.AGAIN.value),
        ).card().due
        val hard = FsrsScheduling.review(
            SchedulingPolicy(), newCard(), Rating.HARD, now, FsrsScheduling.seed("c", Rating.HARD.value),
        ).card().due
        assertTrue(hard.isAfter(again))
    }

    @Test
    fun `blank steps graduate a new card on its very first answer`() {
        val policy = SchedulingPolicy(learningSteps = emptyList(), relearningSteps = emptyList())
        for (rating in Rating.values()) {
            val card = FsrsScheduling.review(
                policy, newCard(), rating, now, FsrsScheduling.seed("c", rating.value),
            ).card()
            assertEquals("$rating with no steps", State.REVIEW, card.state)
        }
    }

    // ---- The minimum interval ---------------------------------------------

    @Test
    fun `a minimum interval raises a day scale result that came in under it`() {
        val result = FsrsScheduling.review(
            policy = SchedulingPolicy(minimumIntervalDays = 5),
            card = reviewCard(stability = 1.0),
            rating = Rating.GOOD,
            now = now,
            seed = FsrsScheduling.seed("c", Rating.GOOD.value),
        )
        assertEquals(5L, dueDays(result))
    }

    @Test
    fun `a minimum interval is a floor and never shortens an interval`() {
        // A 100-day stability already schedules well past five days, so the
        // floor must not touch it — a clamp applied the wrong way round would
        // cap every card in the collection at five days.
        val unclamped = FsrsScheduling.review(
            SchedulingPolicy(), reviewCard(stability = 100.0), Rating.GOOD, now, 7L,
        )
        val clamped = FsrsScheduling.review(
            SchedulingPolicy(minimumIntervalDays = 5), reviewCard(stability = 100.0), Rating.GOOD, now, 7L,
        )
        assertTrue(dueDays(unclamped) > 5)
        assertEquals(dueDays(unclamped), dueDays(clamped))
    }

    @Test
    fun `the minimum interval leaves a learning step alone`() {
        // "Come back in ten minutes" and a one-day floor cannot both be true,
        // and the reader set the step more recently than they set the floor.
        val result = FsrsScheduling.review(
            policy = SchedulingPolicy(minimumIntervalDays = 30),
            card = newCard(),
            rating = Rating.AGAIN,
            now = now,
            seed = FsrsScheduling.seed("c", Rating.AGAIN.value),
        )
        assertEquals(State.LEARNING, result.card().state)
        assertEquals(10L, ChronoUnit.MINUTES.between(now, result.card().due))
    }

    @Test
    fun `a maximum interval is respected`() {
        val result = FsrsScheduling.review(
            policy = SchedulingPolicy(maximumIntervalDays = 30),
            card = reviewCard(stability = 5_000.0),
            rating = Rating.GOOD,
            now = now,
            seed = FsrsScheduling.seed("c", Rating.GOOD.value),
        )
        assertTrue(dueDays(result) <= 30)
    }

    // ---- Fuzzing, and the button that must not lie -------------------------

    @Test
    fun `the same card and rating always produce the same interval`() {
        // The regression test for the real bug. Fuzzing used to advance a
        // scheduler-wide Random, so previewing an interval and then grading
        // consumed two draws and the button showed a number the reader did not
        // get. Asking four times in a row must give one answer.
        val policy = SchedulingPolicy()
        val dues = (1..4).map {
            FsrsScheduling.review(
                policy, reviewCard(), Rating.GOOD, now, FsrsScheduling.seed("card-2", Rating.GOOD.value),
            ).card().due
        }
        assertEquals(1, dues.distinct().size)
    }

    @Test
    fun `fuzzing nudges an interval rather than moving it`() {
        val policy = SchedulingPolicy()
        val exact = FsrsScheduling.review(
            policy.copy(fuzzingEnabled = false), reviewCard(), Rating.GOOD, now, 7L,
        )
        val fuzzed = FsrsScheduling.review(
            policy, reviewCard(), Rating.GOOD, now, FsrsScheduling.seed("card-2", Rating.GOOD.value),
        )
        val drift = Math.abs(dueDays(fuzzed) - dueDays(exact))
        assertTrue("fuzzing moved a ${dueDays(exact)} day interval by $drift days", drift <= 5)
    }

    @Test
    fun `fuzzing never touches a learning step`() {
        val withFuzz = FsrsScheduling.review(
            SchedulingPolicy(), newCard(), Rating.AGAIN, now, FsrsScheduling.seed("c", Rating.AGAIN.value),
        )
        assertEquals(10L, ChronoUnit.MINUTES.between(now, withFuzz.card().due))
    }

    @Test
    fun `each rating gets its own seed`() {
        val seeds = Rating.values().map { FsrsScheduling.seed("card-1", it.value) }
        assertEquals(seeds.size, seeds.distinct().size)
    }

    @Test
    fun `a seed is stable across calls, so a rotation cannot change an answer`() {
        assertEquals(
            FsrsScheduling.seed("card-1", 3),
            FsrsScheduling.seed("card-1", 3),
        )
        assertNotEquals(
            FsrsScheduling.seed("card-1", 3),
            FsrsScheduling.seed("card-2", 3),
        )
    }

    // ---- The one formula this app re-implements ---------------------------

    @Test
    fun `the duplicated interval formula agrees with the library`() {
        // Rescheduling an existing card needs an interval from a stability
        // without reviewing the card, which would compound its stability. So the
        // formula is copied — and duplication is a liability, so it is checked
        // against the library's own answer instead of trusted.
        val policy = SchedulingPolicy()
        for (stability in listOf(0.5, 1.0, 3.0, 10.0, 47.0, 300.0)) {
            val card = reviewCard(stability = stability)
            val result = FsrsScheduling.review(
                policy.copy(fuzzingEnabled = false),
                card,
                Rating.GOOD,
                now,
                FsrsScheduling.seed("c", Rating.GOOD.value),
            )
            val fromLibrary = ChronoUnit.DAYS.between(now, result.card().due).toInt()
            assertEquals(
                "stability $stability",
                fromLibrary,
                FsrsScheduling.intervalDaysFor(result.card().stability, policy),
            )
        }
    }

    @Test
    fun `wanting to remember more often shortens the interval`() {
        val dayScale = reviewCard(stability = 50.0)
        val relaxed = FsrsScheduling.intervalDaysFor(
            dayScale.stability, SchedulingPolicy(desiredRetention = 0.75),
        )
        val strict = FsrsScheduling.intervalDaysFor(
            dayScale.stability, SchedulingPolicy(desiredRetention = 0.95),
        )
        assertTrue("$relaxed should be longer than $strict", relaxed > strict)
    }

    @Test
    fun `the interval formula respects the bounds it is given`() {
        val policy = SchedulingPolicy(minimumIntervalDays = 10, maximumIntervalDays = 20)
        assertEquals(10, FsrsScheduling.intervalDaysFor(0.01, policy))
        assertEquals(20, FsrsScheduling.intervalDaysFor(100_000.0, policy))
        assertNotNull(FsrsScheduling.intervalDaysFor(1.0, policy))
    }
}

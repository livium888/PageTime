package com.pagetime.app.data.review

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's side of scheduling: Anki's step syntax, and the bounds every
 * value has to satisfy before it is allowed to reach the scheduler.
 *
 * The parse tests are mostly about what is REJECTED. Accepting `10m 1x` as
 * "10m" would schedule the reader by half of what they typed with nothing on
 * screen to say the other half was dropped, and a scheduling setting that
 * silently does something is worse than one that refuses.
 */
class SchedulingPolicyTest {

    // ---- Anki's step syntax -------------------------------------------------

    @Test
    fun `minutes hours and days all parse`() {
        assertEquals(listOf(Duration.ofMinutes(10)), Steps.parse("10m"))
        assertEquals(listOf(Duration.ofHours(1)), Steps.parse("1h"))
        assertEquals(listOf(Duration.ofDays(2)), Steps.parse("2d"))
        assertEquals(
            listOf(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofDays(1)),
            Steps.parse("1m 10m 1d"),
        )
    }

    @Test
    fun `an hour and sixty minutes are the same step`() {
        assertEquals(Steps.parse("60m"), Steps.parse("1h"))
    }

    @Test
    fun `extra whitespace is not a syntax error`() {
        assertEquals(Steps.parse("1m 10m"), Steps.parse("  1m   10m  "))
    }

    @Test
    fun `blank means no steps rather than unfinished input`() {
        // A real and documented Anki setup: graduate a card on its first answer.
        assertEquals(emptyList<Duration>(), Steps.parse(""))
        assertEquals(emptyList<Duration>(), Steps.parse("   "))
    }

    @Test
    fun `a token that is not a step rejects the whole string`() {
        assertNull(Steps.parse("10m 1x"))
        assertNull(Steps.parse("ten minutes"))
        assertNull(Steps.parse("10"))
        assertNull(Steps.parse("-10m"))
        assertNull(Steps.parse("10m,"))
    }

    @Test
    fun `a step shorter than a minute or longer than a year is refused`() {
        assertNull("0m is an infinite loop, not a step", Steps.parse("0m"))
        assertNull(Steps.parse("366d"))
        assertEquals(listOf(Duration.ofDays(365)), Steps.parse("365d"))
    }

    @Test
    fun `descending steps are a typo and are refused`() {
        // Otherwise answering Good would schedule a card sooner than failing it.
        assertNull(Steps.parse("10m 1m"))
        assertNull(Steps.parse("1d 1h"))
        // Equal is fine: two steps of the same length is a choice, if a dull one.
        assertEquals(listOf(Duration.ofMinutes(10), Duration.ofMinutes(10)), Steps.parse("10m 10m"))
    }

    @Test
    fun `absurdly many steps are refused rather than truncated`() {
        assertNull(Steps.parse((1..9).joinToString(" ") { "${it}m" }))
        assertEquals(
            (1..SchedulingPolicy.MAX_STEP_COUNT).map { Duration.ofMinutes(it.toLong()) },
            Steps.parse((1..SchedulingPolicy.MAX_STEP_COUNT).joinToString(" ") { "${it}m" }),
        )
    }

    @Test
    fun `everything that parses survives a format and parse round trip`() {
        val samples = listOf(
            emptyList(),
            listOf(Duration.ofMinutes(10)),
            listOf(Duration.ofMinutes(1), Duration.ofMinutes(10)),
            listOf(Duration.ofMinutes(90)),
            listOf(Duration.ofHours(4), Duration.ofDays(3)),
        )
        for (steps in samples) {
            assertEquals(steps, Steps.parse(Steps.format(steps)))
        }
    }

    @Test
    fun `formatting speaks the largest whole unit it can`() {
        assertEquals("1h", Steps.format(listOf(Duration.ofMinutes(60))))
        assertEquals("2d", Steps.format(listOf(Duration.ofHours(48))))
        assertEquals("90m", Steps.format(listOf(Duration.ofMinutes(90))))
        assertEquals("", Steps.format(emptyList()))
    }

    // ---- The policy's own bounds -------------------------------------------

    @Test
    fun `the default is one ten minute step, not Anki's two`() {
        // This single value is the whole answer to "why does a card I knew come
        // back in ten minutes": with one step, Good on a new card is the LAST
        // step, so the card graduates and FSRS gives it days.
        val policy = SchedulingPolicy()
        assertEquals(listOf(Duration.ofMinutes(10)), policy.learningSteps)
        assertEquals(listOf(Duration.ofMinutes(10)), policy.relearningSteps)
        assertFalse(policy.graduatesImmediately)
    }

    @Test
    fun `no steps at all means a card graduates immediately`() {
        val policy = SchedulingPolicy(learningSteps = emptyList(), relearningSteps = emptyList())
        assertTrue(policy.graduatesImmediately)
        // One list empty is not the same thing, and must not read as "no steps".
        assertFalse(SchedulingPolicy(learningSteps = emptyList()).graduatesImmediately)
    }

    @Test
    fun `normalising clamps retention to the range where it is a trade and not a mistake`() {
        assertEquals(
            SchedulingPolicy.MIN_RETENTION,
            SchedulingPolicy(desiredRetention = 0.1).normalised().desiredRetention,
            0.0001,
        )
        assertEquals(
            SchedulingPolicy.MAX_RETENTION,
            SchedulingPolicy(desiredRetention = 1.5).normalised().desiredRetention,
            0.0001,
        )
        assertEquals(0.85, SchedulingPolicy(desiredRetention = 0.85).normalised().desiredRetention, 0.0001)
    }

    @Test
    fun `normalising cannot leave the ceiling below the floor`() {
        // A card would have no legal interval, and the two settings would
        // silently contradict each other.
        val policy = SchedulingPolicy(minimumIntervalDays = 30, maximumIntervalDays = 5).normalised()
        assertEquals(30, policy.minimumIntervalDays)
        assertEquals(30, policy.maximumIntervalDays)
    }

    @Test
    fun `normalising clamps both intervals into their own ranges`() {
        val low = SchedulingPolicy(minimumIntervalDays = -7, maximumIntervalDays = 0).normalised()
        assertEquals(SchedulingPolicy.MIN_MINIMUM_INTERVAL_DAYS, low.minimumIntervalDays)
        assertEquals(SchedulingPolicy.MIN_MAXIMUM_INTERVAL_DAYS, low.maximumIntervalDays)

        val high = SchedulingPolicy(maximumIntervalDays = 999_999).normalised()
        assertEquals(SchedulingPolicy.MAX_MAXIMUM_INTERVAL_DAYS, high.maximumIntervalDays)
    }

    @Test
    fun `normalising truncates a step list that is too long`() {
        val many = (1..20).map { Duration.ofMinutes(it.toLong()) }
        assertEquals(SchedulingPolicy.MAX_STEP_COUNT, SchedulingPolicy(learningSteps = many).normalised().learningSteps.size)
    }
}

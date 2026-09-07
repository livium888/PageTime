package com.pagetime.app.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One review sitting.
 *
 * The rule worth testing is what happens to a card you got wrong. Failing it
 * and moving on would teach nothing — the value of retrieval practice is the
 * successful retrieval — and showing it again immediately would be worse than
 * nothing, because reading an answer back a second later is not remembering.
 */
class ReviewSessionTest {

    private fun ids(vararg s: String) = s.toList()

    @Test
    fun `a sitting starts with what it was given`() {
        val state = ReviewSession.start(ids("A", "B", "C"))
        assertEquals("A", state.current)
        assertEquals(3, state.started)
        assertFalse(state.finished)
        assertEquals(0f, state.progress, 0f)
    }

    @Test
    fun `a failed card comes back later in the same sitting`() {
        var state = ReviewSession.start(ids("A", "B", "C", "D", "E"))
        state = ReviewSession.grade(state, failed = true)

        assertEquals(listOf("B", "C", "D", "A", "E"), state.queue)
        assertEquals(1, state.lapses)
        // Not graduated: the reader has not yet retrieved it.
        assertTrue(state.graduated.isEmpty())
    }

    @Test
    fun `a card retrieved on the second attempt still graduates`() {
        var state = ReviewSession.start(ids("A", "B", "C", "D", "E"))
        state = ReviewSession.grade(state, failed = true)
        repeat(3) { state = ReviewSession.grade(state, failed = false) }
        assertEquals("A", state.current)

        state = ReviewSession.grade(state, failed = false)
        assertTrue("A" in state.graduated)
        assertEquals(listOf("E"), state.queue)

        state = ReviewSession.grade(state, failed = false)
        assertTrue(state.finished)
        assertEquals(1f, state.progress, 0f)
        // Six answers for five cards: the lapse cost an extra attempt.
        assertEquals(6, state.answered)
        assertEquals(1, state.lapses)
    }

    @Test
    fun `progress counts cards and not answers`() {
        // Six failures on one card is not six cards' worth of work, and a bar
        // that says so is lying in the direction that feels worst.
        var state = ReviewSession.start(ids("A", "B"))
        repeat(6) { state = ReviewSession.grade(state, failed = true) }
        assertEquals(0f, state.progress, 0f)
        assertEquals(6, state.answered)
    }

    @Test
    fun `with fewer cards left than the gap the failed one simply goes last`() {
        var state = ReviewSession.start(ids("P", "Q"))
        state = ReviewSession.grade(state, failed = true)
        assertEquals(listOf("Q", "P"), state.queue)
    }

    @Test
    fun `the last card standing repeats until it is answered`() {
        var state = ReviewSession.start(ids("Z"))
        state = ReviewSession.grade(state, failed = true)
        assertEquals(listOf("Z"), state.queue)
        assertFalse(state.finished)

        state = ReviewSession.grade(state, failed = false)
        assertTrue(state.finished)
        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun `a skipped card leaves the sitting without counting against it`() {
        var state = ReviewSession.start(ids("A", "B", "C"))
        state = ReviewSession.skip(state)

        assertEquals(listOf("B", "C"), state.queue)
        assertEquals(2, state.started)
        assertEquals(0, state.answered)

        state = ReviewSession.grade(state, failed = false)
        state = ReviewSession.grade(state, failed = false)
        // Finishing what remains is finishing, not two thirds of a job.
        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun `a sitting is capped and never shows the same card twice`() {
        assertEquals(listOf("A", "B"), ReviewSession.start(ids("A", "A", "B")).queue)
        val many = (0 until 80).map { it.toString() }
        assertEquals(ReviewSession.MAX_SESSION, ReviewSession.start(many).queue.size)
    }

    @Test
    fun `an empty sitting is already over`() {
        val state = ReviewSession.start(emptyList())
        assertTrue(state.finished)
        assertNull(state.current)
        assertEquals(1f, state.progress, 0f)
        // Grading nothing is a no-op rather than a crash.
        assertEquals(state, ReviewSession.grade(state, failed = false))
        assertEquals(state, ReviewSession.skip(state))
    }

    @Test
    fun `a card due later today is due now`() {
        // Making the reader come back this evening for one card is worse for
        // memory and much worse for the habit.
        val now = 1_000_000L
        assertEquals(now + 16L * 60L * 60L * 1000L, ReviewSession.dueThreshold(now))
    }
}

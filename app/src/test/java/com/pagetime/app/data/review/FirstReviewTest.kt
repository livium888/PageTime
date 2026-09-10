package com.pagetime.app.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstReviewTest {

    @Test
    fun `the reading chair offers three answers, not four`() {
        assertEquals(
            listOf(FirstReview.AGAIN, FirstReview.HARD, FirstReview.GOOD),
            FirstReview.OFFERED_IN_THE_CHAIR,
        )
        assertFalse(FirstReview.offeredInTheChair(FirstReview.EASY))
    }

    @Test
    fun `easy is not something you can claim about a sentence you just read`() {
        assertEquals(FirstReview.GOOD, FirstReview.inTheChair(FirstReview.EASY))
    }

    @Test
    fun `the three honest answers pass through untouched`() {
        assertEquals(FirstReview.AGAIN, FirstReview.inTheChair(FirstReview.AGAIN))
        assertEquals(FirstReview.HARD, FirstReview.inTheChair(FirstReview.HARD))
        assertEquals(FirstReview.GOOD, FirstReview.inTheChair(FirstReview.GOOD))
    }

    @Test
    fun `a rating below again is nonsense and becomes again`() {
        assertEquals(FirstReview.AGAIN, FirstReview.inTheChair(0))
        assertEquals(FirstReview.AGAIN, FirstReview.inTheChair(-3))
    }

    @Test
    fun `a rating above easy cannot smuggle in a longer interval`() {
        assertEquals(FirstReview.GOOD, FirstReview.inTheChair(9))
    }

    @Test
    fun `failing a card keeps it`() {
        // The card you could not answer is the most valuable one in the deck.
        // Dropping it for being hard is how a deck fills up with things you
        // already know.
        assertTrue(FirstReview.keeps(FirstReview.AGAIN))
        assertTrue(FirstReview.keeps(FirstReview.HARD))
        assertTrue(FirstReview.keeps(FirstReview.GOOD))
    }

    @Test
    fun `an unanswered prompt is a first sighting`() {
        assertTrue(FirstReview.isFirstSighting("pending", 0))
    }

    @Test
    fun `a card kept from the flashcard list is still a first sighting`() {
        // Accepting a card from a list is not answering it. Status alone would
        // call this reviewed when nobody has ever tried to recall it.
        assertTrue(FirstReview.isFirstSighting("kept", 0))
    }

    @Test
    fun `a card that has been answered is not a first sighting`() {
        assertFalse(FirstReview.isFirstSighting("kept", 1))
        assertFalse(FirstReview.isFirstSighting("pending", 4))
    }

    @Test
    fun `a thrown-away card is never a first sighting`() {
        assertFalse(FirstReview.isFirstSighting("skipped", 0))
    }
}

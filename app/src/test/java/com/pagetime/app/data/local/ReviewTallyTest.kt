package com.pagetime.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the app is allowed to claim about the reader's memory.
 *
 * Small, and the one case that matters is the empty one: a reader who has
 * answered nothing has not remembered 0% of anything.
 */
class ReviewTallyTest {

    @Test
    fun `no reviews is not zero percent`() {
        // Zero would read as "measured, and you failed everything" on the
        // screen of someone who has just made their first cards.
        assertNull(ReviewTally().recall)
        assertNull(ReviewTally(reviews = 0, remembered = 0, cards = 4).recall)
    }

    @Test
    fun `recall is remembered over reviews`() {
        assertEquals(0.75f, ReviewTally(reviews = 8, remembered = 6, cards = 5).recall!!, 0.0001f)
    }

    @Test
    fun `every answer forgotten is zero, not absent`() {
        assertEquals(0f, ReviewTally(reviews = 3, remembered = 0, cards = 3).recall!!, 0.0001f)
    }
}

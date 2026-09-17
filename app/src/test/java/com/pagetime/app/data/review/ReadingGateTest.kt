package com.pagetime.app.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingGateTest {

    @Test
    fun `fewer than a batch never pauses`() {
        assertFalse(ReadingGate.shouldPauseForChoice(answeredSinceLastPause = 0, remainingDue = 40))
        assertFalse(ReadingGate.shouldPauseForChoice(answeredSinceLastPause = 4, remainingDue = 40))
    }

    @Test
    fun `exactly a batch pauses when more remain`() {
        assertTrue(ReadingGate.shouldPauseForChoice(answeredSinceLastPause = 5, remainingDue = 1))
    }

    @Test
    fun `more than a batch still pauses`() {
        // A failed card requeued mid-batch can push the count past five before
        // the queue empties; that is still a completed sitting, not a reason
        // to wait for a rounder number.
        assertTrue(ReadingGate.shouldPauseForChoice(answeredSinceLastPause = 7, remainingDue = 3))
    }

    @Test
    fun `nothing left due never pauses, however many were answered`() {
        // Exhausting the queue is its own answer — there is nothing to offer
        // "five more" OF, so the reader goes straight to the book instead of
        // being asked a question with only one possible response.
        assertFalse(ReadingGate.shouldPauseForChoice(answeredSinceLastPause = 5, remainingDue = 0))
        assertFalse(ReadingGate.shouldPauseForChoice(answeredSinceLastPause = 40, remainingDue = 0))
    }

    @Test
    fun `the batch size is five`() {
        // Pinned because callers reset their own counter against this constant;
        // a silent change here would desync the reset from the trigger.
        assertEquals(5, ReadingGate.BATCH_SIZE)
    }
}

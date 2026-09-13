package com.pagetime.app.ui.screens.review

import com.pagetime.app.data.LumenRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the decisions behind the compact answer bar.
 *
 * The drawing itself needs a phone; what does not is where the interval goes
 * and when there is anything to say about the last answer — which is the part
 * that silently goes wrong, because a rating with no caption and a feedback
 * line with no feedback both render as something plausible rather than as an
 * error.
 */
class AnswerBarTest {

    @Test
    fun `an interval is drawn above the label`() {
        assertEquals(listOf("2d", "Good"), answerButtonLines("2d", "Good"))
    }

    @Test
    fun `a rating with no interval shows its label alone`() {
        // The reading chair computes no interval previews, so this is the path
        // every chair button takes.
        assertEquals(listOf("Knew it"), answerButtonLines(null, "Knew it"))
        assertEquals(listOf("Knew it"), answerButtonLines("", "Knew it"))
        assertEquals(listOf("Knew it"), answerButtonLines("   ", "Knew it"))
    }

    @Test
    fun `the interval is always the first line`() {
        LumenRating.entries.forEach { rating ->
            val lines = answerButtonLines("<1m", rating.label)
            assertEquals(2, lines.size)
            assertEquals("<1m", lines.first())
            assertEquals(rating.label, lines.last())
        }
    }

    @Test
    fun `no feedback is silent rather than empty`() {
        assertNull(reviewFeedbackLine(ReviewUiState()))
    }

    @Test
    fun `a correct answer says what it earned and what it cost`() {
        val line = reviewFeedbackLine(
            ReviewUiState(
                earnedFeedback = "+30s",
                lastInterval = "3d",
                totalEarnedThisSitting = 120,
            )
        )
        assertEquals("+30s · next in 3d · 120s this sitting", line)
    }

    @Test
    fun `a lapse still reports the schedule`() {
        // Again earns nothing, so there is no "+0s" to show — but the reader
        // still needs to know the card is coming back in ten minutes.
        val line = reviewFeedbackLine(ReviewUiState(lastInterval = "10m", totalEarnedThisSitting = 90))
        assertEquals("next in 10m · 90s this sitting", line)
    }

    @Test
    fun `a running total with no event is not worth saying`() {
        // This is the case the permanent note used to cover, and the whole
        // reason it was removed: a counter updating on a card the reader has
        // not answered yet is noise, not feedback.
        assertNull(reviewFeedbackLine(ReviewUiState(totalEarnedThisSitting = 300)))
    }

    @Test
    fun `blank strings are treated as absent`() {
        assertNull(reviewFeedbackLine(ReviewUiState(earnedFeedback = "  ", lastInterval = "")))
    }
}

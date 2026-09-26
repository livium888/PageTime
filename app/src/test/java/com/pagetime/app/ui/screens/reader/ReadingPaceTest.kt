package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPaceTest {

    /**
     * The false positive this class exists to fix: a short document (6,000
     * words — a long article or YouTube transcript) read at a very fast but
     * still human skim (900 wpm) covers 900/6000 = 15% of the whole thing in
     * one minute — over the OLD fixed ceiling of 12%/minute, which measured
     * pace as book-fraction rather than words and so had no way to tell a
     * short document apart from a dense page of a long one. Once the word
     * count is known, the same 900 wpm reads as exactly what it is: fast,
     * but well under this class's own generous ceiling.
     */
    @Test
    fun `a fast but human pace on a short document is not flagged once word count is known`() {
        val totalWords = 6_000
        val wordsPerMinuteRead = 900f
        val forwardProgressDelta = wordsPerMinuteRead / totalWords // ≈ 0.15 (15%) in one minute

        assertFalse(
            "a real 900 wpm pace must not be flagged once the book's word count is known",
            ReadingPace.isTooFast(
                forwardProgressDelta = forwardProgressDelta,
                minutesElapsed = 1f,
                totalWords = totalWords,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
    }

    @Test
    fun `the same short-document pace WOULD have been flagged by the old fraction-only check`() {
        val totalWords = 6_000
        val wordsPerMinuteRead = 900f
        val forwardProgressDelta = wordsPerMinuteRead / totalWords

        assertTrue(
            "sanity check: this is exactly the false positive the word-count path fixes",
            ReadingPace.isTooFast(
                forwardProgressDelta = forwardProgressDelta,
                minutesElapsed = 1f,
                totalWords = null,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
    }

    @Test
    fun `a genuinely implausible pace is still caught even when word count is known`() {
        assertTrue(
            ReadingPace.isTooFast(
                forwardProgressDelta = 0.5f,
                minutesElapsed = 1f,
                totalWords = 80_000,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
    }

    @Test
    fun `without a word count, the fraction ceiling still applies unchanged`() {
        assertTrue(
            ReadingPace.isTooFast(
                forwardProgressDelta = 0.20f,
                minutesElapsed = 1f,
                totalWords = null,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
        assertFalse(
            ReadingPace.isTooFast(
                forwardProgressDelta = 0.05f,
                minutesElapsed = 1f,
                totalWords = null,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
    }

    @Test
    fun `a zero or negative word count falls back to the fraction ceiling`() {
        assertTrue(
            ReadingPace.isTooFast(
                forwardProgressDelta = 0.20f,
                minutesElapsed = 1f,
                totalWords = 0,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
    }

    @Test
    fun `no time elapsed is never flagged, regardless of delta`() {
        assertFalse(
            ReadingPace.isTooFast(
                forwardProgressDelta = 1f,
                minutesElapsed = 0f,
                totalWords = 80_000,
                maxFractionPerMinute = 0.12f,
                maxWordsPerMinute = 1000f,
            )
        )
    }
}

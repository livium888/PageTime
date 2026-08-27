package com.pagetime.app.data.learning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher is the guard that keeps Explain Back from offering concepts
 * grounded in unrelated parts of the book. These cases cover exact, drifted,
 * and fuzzy grounding — and, most importantly, rejection of unrelated text.
 */
class ConceptRangeMatcherTest {

    @Test
    fun `quote contained in the range is relevant`() {
        val range = "The battery works because zinc dissolves in the acid and electrons flow through the wire."
        assertTrue(
            ConceptRangeMatcher.isRelevant("Galvanic action", "zinc dissolves in the acid", range)
        )
    }

    @Test
    fun `label contained in the range is relevant even when the quote is junk`() {
        val range = "Franklin argued that electricity was a single fluid that could be added or removed."
        assertTrue(
            ConceptRangeMatcher.isRelevant("single fluid", "some quote from elsewhere", range)
        )
    }

    @Test
    fun `unrelated label and quote are rejected`() {
        val range = "The committee debated tariffs for most of the afternoon session."
        assertFalse(
            ConceptRangeMatcher.isRelevant("Hamilton debt plan", "assumption of state debts", range)
        )
    }

    @Test
    fun `punctuation and whitespace drift still match`() {
        val range = "He said: “a republic, if you can keep it” — and left the hall."
        assertTrue(
            ConceptRangeMatcher.isRelevant("The Republic", "A Republic, if you can keep it!", range)
        )
    }

    @Test
    fun `quote matching most of its significant words is relevant`() {
        val quote = "congress assumed the state debts and funded them"
        val range = "Hamilton proposed that congress assumed the state debts while Virginia objected."
        assertTrue(ConceptRangeMatcher.isRelevant("Assumption", quote, range))
    }

    @Test
    fun `quote matching too few words is rejected`() {
        val quote = "congress assumed the state debts and funded them"
        val range = "congress assumed the debts in a noisy session."
        assertFalse(ConceptRangeMatcher.isRelevant("Debt assumption", quote, range))
    }

    @Test
    fun `empty range text is never relevant`() {
        assertFalse(ConceptRangeMatcher.isRelevant("battery", "zinc dissolves in acid", ""))
    }

    @Test
    fun `quotes too short to trust are ignored when the label is absent`() {
        val range = "The committee debated tariffs for most of the afternoon session."
        assertFalse(ConceptRangeMatcher.isRelevant("unrelated thing", "zinc", range))
    }

    @Test
    fun `label matching inside a longer word is rejected`() {
        val range = "The republican faction debated the tariff schedule all afternoon."
        assertFalse(ConceptRangeMatcher.isRelevant("Republic", "another place entirely", range))
    }

    @Test
    fun `label at word boundary still matches`() {
        val range = "The republic survived because its institutions balanced competing factions."
        assertTrue(ConceptRangeMatcher.isRelevant("Republic", "nothing relevant here", range))
    }
}

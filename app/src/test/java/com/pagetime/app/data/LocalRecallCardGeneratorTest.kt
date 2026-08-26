package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalRecallCardGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecallCardGeneratorTest {

    private fun context(text: String) = LearningContext(
        bookId = "book",
        bookTitle = "Book",
        chapterIndex = 1,
        chapterTitle = "Chapter 2",
        recentText = text,
        sourceFormat = "txt"
    )

    @Test
    fun `creates cloze and MCQ cards from a completed reading window`() {
        val sentence = "The careful navigator crossed the ancient valley before sunrise, carrying supplies for the remote settlement."
        val ctx = context(
            "$sentence Another sentence follows with enough words to preserve the context for review. " +
                "And one more sentence about important things that matter for the reader."
        )

        val cards = LocalRecallCardGenerator.generate(ctx)

        assertTrue(cards.isNotEmpty())
        val hasClozeOrMcq = cards.any { card ->
            card.cardType == "cloze" && card.question.contains("{{c1::") ||
                card.cardType == "mcq" && card.question.contains("______")
        }
        assertTrue("Expected at least one cloze or MCQ card", hasClozeOrMcq)
        assertTrue(cards.all { it.sourceQuote.isNotBlank() })
    }

    @Test
    fun `ignores short fragments that cannot support recall`() {
        val ctx = context("Too short. No useful context.")
        assertTrue(LocalRecallCardGenerator.generate(ctx).isEmpty())
    }

    @Test
    fun `cloze target is not a stop word`() {
        val ctx = context(
            "The ancient theory of relativity is fundamental to modern physics. " +
                "Scientists have confirmed that energy and mass are equivalent. " +
                "This principle explains why nuclear reactions release enormous amounts of energy."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        val cloze = cards.firstOrNull { it.cardType == "cloze" } ?: return
        val answer = cloze.question
            .substringAfter("{{c1::")
            .substringBefore("}}")
            .trim()
        assertFalse("Cloze target must not be a stop word: $answer",
            answer.lowercase() in setOf("the", "a", "an", "is", "are", "was", "were", "and", "or", "but", "for", "that", "this"))
    }

    @Test
    fun `cloze prefers words after definition signals`() {
        val ctx = context(
            "The mitochondria is the powerhouse of the cell. " +
                "Energy production occurs through a series of chemical reactions. " +
                "These reactions are essential for sustaining life in all organisms."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        val cloze = cards.firstOrNull { it.cardType == "cloze" } ?: return
        val answer = cloze.question
            .substringAfter("{{c1::")
            .substringBefore("}}")
            .trim()
        // The word after "is" should be preferred.
        assertEquals("powerhouse", answer.lowercase())
    }

    @Test
    fun `qa card uses definition pattern when available`() {
        val ctx = context(
            "Photosynthesis is the process by which plants convert sunlight into chemical energy. " +
                "This mechanism depends on chlorophyll molecules in the leaf. " +
                "The reaction produces glucose and oxygen as byproducts."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        val qa = cards.firstOrNull { it.cardType == "qa" } ?: return
        assertTrue("Q&A should ask about the definition subject",
            qa.question.lowercase().contains("photosynthesis") || qa.question.lowercase().contains("process"))
    }

    @Test
    fun `mcq options come from the same sentence`() {
        val ctx = context(
            "The experiment tested whether temperature affects enzyme activity rates. " +
                "Results showed that higher temperatures increase reaction speed. " +
                "However, extreme heat denatures the protein structure."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        val mcq = cards.firstOrNull { it.cardType == "mcq" } ?: return
        assertNotNull("MCQ should have options", mcq.mcqOptions)
        assertTrue("MCQ should have 3-4 options", mcq.mcqOptions!!.size in 3..4)
        // The correct answer should be among the options.
        assertTrue("Correct answer must be in the options",
            mcq.mcqOptions!!.any { it.equals(mcq.answer, ignoreCase = true) })
    }

    @Test
    fun `all cards have valid source quotes`() {
        val ctx = context(
            "Darwin proposed that species evolve through natural selection over long periods. " +
                "This theory revolutionized our understanding of biological diversity. " +
                "Fossil records provide evidence supporting gradual change in organisms."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { it.sourceQuote.length in 20..500 })
    }

    @Test
    fun `generates up to the requested limit`() {
        val ctx = context(
            "The ancient Romans built aqueducts to transport water across vast distances. " +
                "These engineering marvels used gravity to maintain flow through carefully graded channels. " +
                "The system provided clean water to cities supporting hundreds of thousands of people."
        )
        val cards3 = LocalRecallCardGenerator.generate(ctx, limit = 3)
        val cards2 = LocalRecallCardGenerator.generate(ctx, limit = 2)
        assertTrue("Limit 3 should produce at most 3 cards", cards3.size <= 3)
        assertTrue("Limit 2 should produce at most 2 cards", cards2.size <= 2)
        assertTrue("Limit 2 should produce fewer or equal cards", cards2.size <= cards3.size)
    }
}

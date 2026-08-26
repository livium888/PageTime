package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalRecallCardGenerator
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
    fun `creates MCQ cards from a completed reading window`() {
        val sentence = "The careful navigator crossed the ancient valley before sunrise, carrying supplies for the remote settlement."
        val ctx = context(
            "$sentence Another sentence follows with enough words to preserve the context for review. " +
                "And one more sentence about important things that matter for the reader."
        )

        val cards = LocalRecallCardGenerator.generate(ctx)

        assertTrue(cards.isNotEmpty())
        // Every card must be MCQ.
        assertTrue("All cards must be MCQ", cards.all { it.cardType == "mcq" })
        assertTrue(cards.all { it.mcqOptions != null && it.mcqOptions!!.size >= 3 })
        assertTrue(cards.all { it.sourceQuote.isNotBlank() })
    }

    @Test
    fun `ignores short fragments that cannot support recall`() {
        val ctx = context("Too short. No useful context.")
        assertTrue(LocalRecallCardGenerator.generate(ctx).isEmpty())
    }

    @Test
    fun `every MCQ answer appears in its options`() {
        val ctx = context(
            "The ancient theory of relativity is fundamental to modern physics. " +
                "Scientists have confirmed that energy and mass are equivalent. " +
                "This principle explains why nuclear reactions release enormous amounts of energy."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        cards.forEach { card ->
            val opts = card.mcqOptions ?: return@forEach
            assertTrue(
                "Answer '${card.answer}' must appear in options for: ${card.question}",
                opts.any { it.equals(card.answer, ignoreCase = true) }
            )
        }
    }

    @Test
    fun `MCQ options have at least 3 choices`() {
        val ctx = context(
            "The experiment tested whether temperature affects enzyme activity rates. " +
                "Results showed that higher temperatures increase reaction speed. " +
                "However, extreme heat denatures the protein structure."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        assertTrue(cards.isNotEmpty())
        cards.forEach { card ->
            assertNotNull("MCQ should have options", card.mcqOptions)
            assertTrue(
                "MCQ should have 3-4 options, got ${card.mcqOptions!!.size}",
                card.mcqOptions!!.size in 3..4
            )
        }
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

    @Test
    fun `cards target different concepts within the same chapter`() {
        val ctx = context(
            "Photosynthesis converts solar energy into chemical energy in plants. " +
                "The mitochondria is the powerhouse of the cell and generates ATP. " +
                "Chloroplasts are the organelles where photosynthesis takes place. " +
                "Cellular respiration breaks down glucose to release energy for the cell."
        )
        val cards = LocalRecallCardGenerator.generate(ctx)
        assertTrue(cards.size >= 2)
        // Each question should reference different content (different blanked-out terms).
        val answers = cards.map { it.answer.lowercase() }.toSet()
        assertTrue("Should have distinct answers", answers.size >= 2)
    }
}

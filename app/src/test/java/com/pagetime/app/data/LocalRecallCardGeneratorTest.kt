package com.pagetime.app.data

import com.pagetime.app.data.learning.GeneratedLearningCard
import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalRecallCardGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalRecallCardGenerator] no longer produces cards: MCQ generation was
 * switched off when the app moved to the Explain Back chat method, so
 * `generate()` returns nothing whatever it is given.
 *
 * These tests pin that contract, and keep the quality rules that governed the
 * generated cards executable. They apply to every card that comes back, so they
 * are inert while generation is disabled and cover the generator again the
 * moment it starts producing cards.
 */
class LocalRecallCardGeneratorTest {

    private fun context(text: String) = LearningContext(
        bookId = "book",
        bookTitle = "Book",
        chapterIndex = 1,
        chapterTitle = "Chapter 2",
        recentText = text,
        sourceFormat = "txt"
    )

    private val readingWindows = listOf(
        "The careful navigator crossed the ancient valley before sunrise, carrying supplies for the remote settlement. " +
            "Another sentence follows with enough words to preserve the context for review. " +
            "And one more sentence about important things that matter for the reader.",
        "The ancient theory of relativity is fundamental to modern physics. " +
            "Scientists have confirmed that energy and mass are equivalent. " +
            "This principle explains why nuclear reactions release enormous amounts of energy.",
        "The experiment tested whether temperature affects enzyme activity rates. " +
            "Results showed that higher temperatures increase reaction speed. " +
            "However, extreme heat denatures the protein structure.",
        "Darwin proposed that species evolve through natural selection over long periods. " +
            "This theory revolutionized our understanding of biological diversity. " +
            "Fossil records provide evidence supporting gradual change in organisms.",
        "Photosynthesis converts solar energy into chemical energy in plants. " +
            "The mitochondria is the powerhouse of the cell and generates ATP. " +
            "Chloroplasts are the organelles where photosynthesis takes place. " +
            "Cellular respiration breaks down glucose to release energy for the cell."
    )

    /** The rules a generated card had to satisfy, per Wozniak's 20 rules. */
    private fun assertObeysMcqRules(card: GeneratedLearningCard) {
        assertTrue("Every card must be MCQ, got '${card.cardType}'", card.cardType == "mcq")
        val options = card.mcqOptions
        assertTrue("MCQ must carry options: ${card.question}", options != null)
        assertTrue(
            "MCQ should have 3-4 options, got ${options!!.size}",
            options.size in 3..4
        )
        assertTrue(
            "Answer '${card.answer}' must appear in options for: ${card.question}",
            options.any { it.equals(card.answer, ignoreCase = true) }
        )
        assertTrue(
            "Source quote must be a usable length, got ${card.sourceQuote.length}",
            card.sourceQuote.length in 20..500
        )
    }

    @Test
    fun `MCQ generation is disabled in favour of Explain Back`() {
        readingWindows.forEach { window ->
            assertTrue(
                "MCQ generation is disabled, so no cards should come back",
                LocalRecallCardGenerator.generate(context(window)).isEmpty()
            )
        }
    }

    @Test
    fun `ignores short fragments that cannot support recall`() {
        val ctx = context("Too short. No useful context.")
        assertTrue(LocalRecallCardGenerator.generate(ctx).isEmpty())
    }

    @Test
    fun `any card that is generated obeys the MCQ quality rules`() {
        readingWindows.forEach { window ->
            LocalRecallCardGenerator.generate(context(window)).forEach(::assertObeysMcqRules)
        }
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

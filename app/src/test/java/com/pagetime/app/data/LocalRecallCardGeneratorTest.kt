package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalRecallCardGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecallCardGeneratorTest {
    @Test
    fun `creates cloze and MCQ cards from a completed reading window`() {
        val sentence = "The careful navigator crossed the ancient valley before sunrise, carrying supplies for the remote settlement."
        val context = LearningContext(
            bookId = "book",
            bookTitle = "Book",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            recentText = "$sentence Another sentence follows with enough words to preserve the context for review. And one more sentence about important things that matter for the reader.",
            sourceFormat = "txt"
        )

        val cards = LocalRecallCardGenerator.generate(context)

        assertTrue(cards.isNotEmpty())
        // The generator should produce at least one cloze or MCQ card with a blank
        val hasClozeOrMcq = cards.any { card ->
            card.cardType == "cloze" && card.question.contains("{{c1::") ||
                card.cardType == "mcq" && card.question.contains("______")
        }
        assertTrue("Expected at least one cloze or MCQ card", hasClozeOrMcq)
        // All cards should have valid source quotes
        assertTrue(cards.all { it.sourceQuote.isNotBlank() })
    }

    @Test
    fun `ignores short fragments that cannot support recall`() {
        val context = LearningContext(
            bookId = "book",
            bookTitle = "Book",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            recentText = "Too short. No useful context.",
            sourceFormat = "txt"
        )

        assertTrue(LocalRecallCardGenerator.generate(context).isEmpty())
    }
}

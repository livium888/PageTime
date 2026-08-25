package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalRecallCardGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecallCardGeneratorTest {
    @Test
    fun `creates grounded cloze cards from a completed reading window`() {
        val sentence = "The careful navigator crossed the ancient valley before sunrise, carrying supplies for the remote settlement."
        val context = LearningContext(
            bookId = "book",
            bookTitle = "Book",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            recentText = "$sentence Another sentence follows with enough words to preserve the context for review.",
            sourceFormat = "txt"
        )

        val cards = LocalRecallCardGenerator.generate(context)

        assertTrue(cards.isNotEmpty())
        assertTrue(cards.first().question.contains("_____"))
        assertTrue(cards.first().answer in sentence)
        assertTrue(cards.first().sourceQuote == sentence)
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

package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalRecallCardGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecallCardGeneratorTest {
    private fun context(text: String): LearningContext =
        LearningContext(
            bookId = "book",
            bookTitle = "Book",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            recentText = text,
            sourceFormat = "txt",
        )

    @Test
    fun `MCQ generation is disabled and the generator returns no cards`() {
        // Commit ad2795d removed the MCQ card system in favor of the Explain
        // Back chat-based method; LocalRecallCardGenerator intentionally yields
        // nothing now. Pin that contract so re-enabling MCQ cards is a
        // deliberate change instead of an unnoticed regression.
        val text =
            "The careful navigator crossed the ancient valley before sunrise, carrying supplies for the remote settlement. " +
                "Another sentence follows with enough words to preserve the context for review. " +
                "And one more sentence about important things that matter for the reader."

        assertTrue(LocalRecallCardGenerator.generate(context(text)).isEmpty())
        assertTrue(LocalRecallCardGenerator.generate(context(text), limit = 3).isEmpty())
    }

    @Test
    fun `short fragments that cannot support recall also return no cards`() {
        assertTrue(LocalRecallCardGenerator.generate(context("Too short. No useful context.")).isEmpty())
    }
}

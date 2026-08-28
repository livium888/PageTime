package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTranscriptFormattingTest {
    @Test
    fun `short transcript stays in one chunk`() {
        val text = "First paragraph.\n\nSecond paragraph."
        val chunks = GeminiLearningClient.splitTranscriptForFormatting(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().contains("First paragraph."))
        assertTrue(chunks.single().contains("Second paragraph."))
        assertTrue(chunks.single().indexOf("First") < chunks.single().indexOf("Second"))
    }

    @Test
    fun `long transcript splits only between paragraphs and preserves all text`() {
        val paragraphs = (0..5).map { "paragraph-$it " + "x".repeat(5_000) }
        val text = paragraphs.joinToString("\n\n")
        val chunks = GeminiLearningClient.splitTranscriptForFormatting(text)

        assertTrue(chunks.size > 1)
        paragraphs.forEach { paragraph ->
            assertEquals(1, chunks.count { it.contains(paragraph) })
        }
        paragraphs.forEach { paragraph ->
            assertTrue(chunks.any { it.contains(paragraph) })
        }
    }
}

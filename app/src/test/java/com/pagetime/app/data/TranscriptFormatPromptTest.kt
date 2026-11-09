package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatPromptTest {

    private val raw = (1..200).joinToString(" ") { "word$it" }

    @Test
    fun `a reply about as long as the transcript is accepted`() {
        val formatted = "# Heading\n\n" + (1..180).joinToString(" ") { "word$it" }
        assertEquals(formatted, TranscriptFormatPrompt.accept(formatted, raw).getOrNull())
    }

    @Test
    fun `a summary is refused so it cannot replace the transcript`() {
        val summary = (1..30).joinToString(" ") { "word$it" }
        val result = TranscriptFormatPrompt.accept(summary, raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("original was kept"))
    }

    @Test
    fun `an empty reply is refused`() {
        assertTrue(TranscriptFormatPrompt.accept("   ", raw).isFailure)
    }

    @Test
    fun `a reply wrapped in a code fence is unwrapped`() {
        val body = (1..190).joinToString(" ") { "word$it" }
        assertEquals(body, TranscriptFormatPrompt.accept("```markdown\n$body\n```", raw).getOrNull())
    }

    @Test
    fun `an ordinary transcript can be sent and an empty or huge one cannot`() {
        assertNull(TranscriptFormatPrompt.problem(raw))
        assertNotNull(TranscriptFormatPrompt.problem("  "))
        val huge = "word ".repeat(TranscriptFormatPrompt.MAX_WORDS + 1)
        assertTrue(TranscriptFormatPrompt.problem(huge)!!.contains("too long"))
    }

    @Test
    fun `the prompt carries the title and the whole transcript`() {
        val prompt = TranscriptFormatPrompt.build("My Talk", raw)
        assertTrue(prompt.contains("My Talk"))
        assertTrue(prompt.contains("word1 "))
        assertTrue(prompt.contains("word200"))
    }
}

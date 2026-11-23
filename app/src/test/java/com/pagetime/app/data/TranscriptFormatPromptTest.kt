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

    @Test
    fun `the prompt asks for every heading level, tables, bold, italics, lists and quotes`() {
        val prompt = TranscriptFormatPrompt.build("My Talk", raw)
        listOf("\"# \"", "\"## \"", "\"### \"", "\"#### \"", "**Bold**", "*Italics*", "Tables", "| --- | --- |", "Blockquotes", "\"- \"")
            .forEach { assertTrue("missing $it", prompt.contains(it)) }
        assertTrue(prompt.contains("Do not summarize"))
        assertTrue(prompt.contains("do not wrap it in a code block"))
    }

    @Test
    fun `marks are not words, so formatting cannot pad a short reply past the check`() {
        assertEquals(3, TranscriptFormatPrompt.words("# One | two --- three"))
        val padded = "| --- | --- | --- |\n".repeat(200) + "# A\n\nonly a few real words here"
        assertTrue(TranscriptFormatPrompt.accept(padded, raw).isFailure)
    }

    @Test
    fun `a heavily formatted reply with all the words is accepted and renders`() {
        val words = (1..200).map { "word$it" }
        val reply = "# Title\n\n## Part\n\n" + words.take(100).joinToString(" ") +
            "\n\n| A | B |\n|---|---|\n| " + words[100] + " | " + words[101] + " |\n\n**" +
            words.drop(102).joinToString(" ") + "**"
        val text = TranscriptFormatPrompt.accept(reply, raw).getOrThrow()
        val c = com.pagetime.app.data.library.MarkdownText.convert(text)
        assertTrue(c.spans.any { it.kind == com.pagetime.app.data.library.MarkdownText.Kind.HEADING && it.level == 2 })
        assertTrue(!c.text.contains("|"))
    }
}

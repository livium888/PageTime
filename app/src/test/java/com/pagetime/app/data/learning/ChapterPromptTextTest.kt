package com.pagetime.app.data.learning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The instructions actually contain the book.
 *
 * This test exists because they once did not. Every template marker in the
 * prompt shipped escaped as a literal dollar sign, so the model was sent
 * "BOOK: ${'$'}bookTitle" and "PASSAGES: ${'$'}numbered" — the request went out
 * with none of the passages in it. The compiler had no complaint worth failing
 * a build over, no test could reach a string built inside a suspend function
 * behind a network call, and the only symptom was a request that failed for an
 * unrelated-looking reason.
 *
 * A prompt is an input to a paid, remote, non-deterministic system. It is worth
 * one assertion that it says what it is supposed to.
 */
class ChapterPromptTextTest {

    private val passages = listOf(
        "The Continental System was Napoleon's attempt to defeat Britain economically.",
        "Smuggling was more profitable than compliance.",
    )

    private fun build() = ChapterPromptText.build("Napoleon", "Chapter One", passages)

    @Test
    fun `the passages are in the prompt`() {
        val text = build()
        passages.forEach { passage ->
            assertTrue("Prompt is missing a passage:\n$text", text.contains(passage))
        }
    }

    @Test
    fun `the book and chapter are in the prompt`() {
        val text = build()
        assertTrue(text.contains("Napoleon"))
        assertTrue(text.contains("Chapter One"))
    }

    /**
     * The assertion that would have caught the original bug outright: an
     * un-interpolated template marker left in the text.
     */
    @Test
    fun `no template markers survive into the prompt`() {
        val text = build()
        listOf("\$bookTitle", "\$chapterTitle", "\$numbered", "\${").forEach { marker ->
            assertFalse("Un-interpolated $marker in the prompt:\n$text", text.contains(marker))
        }
    }

    @Test
    fun `passages are numbered from zero so the model can cite them back`() {
        // passageIndex is how a returned prompt is tied to the passage it came
        // from, and to the offset that decides where it surfaces. Numbering
        // from one would misfile every question by a passage.
        val text = build()
        assertTrue(text.contains("[0]"))
        assertTrue(text.contains("[1]"))
        assertFalse(text.contains("[2]"))
    }

    @Test
    fun `an empty chapter still produces well formed instructions`() {
        val text = ChapterPromptText.build("Book", "Chapter", emptyList())
        assertTrue(text.contains("PASSAGES:"))
        assertFalse(text.contains("[0]"))
    }
}

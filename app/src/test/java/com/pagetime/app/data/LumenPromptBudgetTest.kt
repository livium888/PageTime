package com.pagetime.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture prompt must fit the on-device model's token budget. Overflowing
 * it does not raise an error: MediaPipe aborts the process from native code,
 * which is why the crash never produced a Java crash log. These tests pin the
 * invariant so a longer prompt or a wider capture window fails here instead of
 * on a reader's phone.
 */
class LumenPromptBudgetTest {

    /** Reply budget requested by the capture driver in [LumenLocalDraft]. */
    private val replyTokens = 384

    private fun assertFitsBudget(prompt: String, label: String) {
        val tokens = LlmTokenBudget.estimateTokens(prompt)
        val budget = LlmTokenBudget.inputBudget(replyTokens)
        assertTrue(
            "$label needs ~$tokens tokens but only $budget are available for input",
            tokens <= budget
        )
    }

    @Test
    fun `a full capture window fits the budget`() {
        // The widest passage the reader can hand over: LumenCapture centres a
        // window of DEFAULT_RADIUS_CHARS on each side of the position.
        val passage = "word ".repeat(LumenCapture.DEFAULT_RADIUS_CHARS * 2 / 5)
        assertFitsBudget(LumenAiPrompts.cardDraft(passage, "A Book"), "Full capture window")
        assertFitsBudget(LumenAiPrompts.cardDraftStrict(passage, "A Book"), "Strict retry")
    }

    @Test
    fun `an oversized passage is trimmed to fit`() {
        val passage = "sentence about something. ".repeat(4_000)
        assertFitsBudget(LumenAiPrompts.cardDraft(passage, "A Book"), "Oversized passage")
    }

    @Test
    fun `trimming keeps the middle of the passage, where the reader is`() {
        val passage = buildString {
            append("A".repeat(4_000))
            append(" MIDDLE MARKER. ")
            append("Z".repeat(4_000))
        }
        val trimmed = LumenAiPrompts.trimPassage(passage)
        assertTrue("Trimmed passage keeps the centre", trimmed.contains("MIDDLE MARKER"))
        assertTrue(
            "Trimmed passage respects the cap, got ${trimmed.length}",
            trimmed.length <= LumenAiPrompts.MAX_PASSAGE_CHARS
        )
    }

    @Test
    fun `a passage within the cap is left untouched`() {
        val passage = "A short captured passage that needs no trimming at all."
        assertTrue(LumenAiPrompts.trimPassage(passage) == passage)
    }
}

package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture prompt is editable in Settings, so it is now user input: it has
 * to be substituted, bounded and validated like any other.
 */
class LumenPromptTemplateTest {

    private val passage = "A passage the reader captured while reading a book about something."

    private fun ok(text: String) = Result.success(LlmResult(text, LlmProviderKind.OFFLINE))

    @Test
    fun `render fills both placeholders`() {
        val rendered = LumenAiPrompts.render(
            "Book: {{book}}\nText: {{passage}}",
            passage,
            "Sapiens"
        )

        assertEquals("Book: Sapiens\nText: $passage", rendered)
    }

    @Test
    fun `a tailored template is still bounded by the passage cap`() {
        // The cap is what keeps the prompt inside the model's budget. A reader
        // editing the prompt must not be able to switch that protection off.
        val huge = "sentence about something. ".repeat(4_000)

        val rendered = LumenAiPrompts.render("{{passage}}", huge, "A Book")

        assertTrue(
            "Rendered passage was ${rendered.length} chars",
            rendered.length <= LumenAiPrompts.MAX_PASSAGE_CHARS
        )
    }

    @Test
    fun `a template without the passage placeholder is refused`() {
        assertNotNull(LumenAiPrompts.templateProblem("Write me a card about that book."))
        assertNotNull(LumenAiPrompts.templateProblem("   "))
        assertNull(LumenAiPrompts.templateProblem("Summarise {{passage}} please"))
        assertNull(LumenAiPrompts.templateProblem(LumenAiPrompts.DEFAULT_CARD_TEMPLATE))
    }

    @Test
    fun `the built-in prompt fits the budget it is measured against`() {
        val budget = LlmTokenBudget.inputBudget(LumenLocalDraft.REPLY_TOKENS)

        assertTrue(
            "Built-in prompt costs ${LumenAiPrompts.worstCaseTokens(LumenAiPrompts.DEFAULT_CARD_TEMPLATE)}" +
                " of $budget",
            LumenAiPrompts.worstCaseTokens(LumenAiPrompts.DEFAULT_CARD_TEMPLATE) <= budget
        )
    }

    @Test
    fun `the first attempt uses the reader's template`() = runTest {
        val prompts = mutableListOf<String>()
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            prompts += request.prompt
            ok(
                """{"front":"A claim worth keeping","back":"Because it explains why the idea holds. """ +
                    """Without that reason the claim is only an assertion."}"""
            )
        }

        LumenLocalDraft.generate(
            call = call,
            passage = passage,
            bookTitle = "Sapiens",
            template = "MY OWN PROMPT about {{passage}}",
        )

        assertTrue(prompts.single().startsWith("MY OWN PROMPT about"))
    }

    @Test
    fun `the retry falls back to the built-in prompt`() = runTest {
        // A tailored prompt that produces nothing usable must not also cost the
        // reader their second chance, so the retry ignores it.
        val prompts = mutableListOf<String>()
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            prompts += request.prompt
            ok("not a card at all")
        }

        LumenLocalDraft.generate(
            call = call,
            passage = passage,
            bookTitle = "Sapiens",
            template = "MY OWN PROMPT about {{passage}}",
        )

        assertEquals(2, prompts.size)
        assertTrue(prompts[0].startsWith("MY OWN PROMPT"))
        assertTrue("The retry is the built-in strict prompt", prompts[1].contains("Reply with ONLY this"))
    }
}

/**
 * Prompt ORDER, which no other test covers and which a well-meaning edit could
 * silently reverse.
 *
 * The token budget every prompt is built against is an estimate; what a given
 * .task bundle actually allows is baked into the file and never reported back.
 * When an input is clamped it is clamped from the end. So whatever sits last is
 * what gets sacrificed, and that has to be the passage — losing its tail costs
 * a card about the first half of what was captured. Losing the INSTRUCTIONS
 * instead leaves the model holding book text with no task, which is how a
 * truncated prompt comes to look like a bad model.
 */
class LumenPromptOrderTest {

    private fun instructionsPrecedePassage(prompt: String, marker: String) {
        val passageAt = prompt.indexOf(marker)
        val rulesAt = prompt.indexOf("front")
        assertTrue("Prompt does not contain the passage marker", passageAt >= 0)
        assertTrue("Prompt does not contain its instructions", rulesAt >= 0)
        assertTrue(
            "Instructions must come BEFORE the passage so a clamp costs the " +
                "passage's tail rather than the whole task description",
            rulesAt < passageAt,
        )
    }

    @Test
    fun `the built-in template puts its instructions first`() {
        instructionsPrecedePassage(
            LumenAiPrompts.DEFAULT_CARD_TEMPLATE,
            LumenAiPrompts.PASSAGE_TOKEN,
        )
    }

    @Test
    fun `the strict retry puts its instructions first`() {
        instructionsPrecedePassage(
            LumenAiPrompts.cardDraftStrict("UNIQUE-PASSAGE-MARKER", "A Book"),
            "UNIQUE-PASSAGE-MARKER",
        )
    }

    @Test
    fun `the different-idea retry puts its instructions first`() {
        instructionsPrecedePassage(
            LumenAiPrompts.cardDraftDifferent("UNIQUE-PASSAGE-MARKER", "A Book", "An older note"),
            "UNIQUE-PASSAGE-MARKER",
        )
    }

    /**
     * The reply instruction is the model's cue to start emitting JSON, so it
     * has to be the last thing it reads. If the passage came after it, the
     * model would be told how to answer and then handed more input.
     */
    @Test
    fun `the reply instruction comes after the passage`() {
        val prompt = LumenAiPrompts.cardDraft("UNIQUE-PASSAGE-MARKER", "A Book")
        assertTrue(
            prompt.indexOf("UNIQUE-PASSAGE-MARKER") < prompt.lastIndexOf("Reply with ONLY"),
        )
    }
}

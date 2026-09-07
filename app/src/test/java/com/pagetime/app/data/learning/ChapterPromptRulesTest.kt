package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between a generated prompt and the reader's memory.
 *
 * A card that gets through here will be rehearsed on a widening schedule for
 * months, so a wrong one is a falsehood installed on purpose. Throwing away a
 * good prompt costs one prompt from a chapter that will make more. The rules
 * are tested in the direction of that asymmetry.
 */
class ChapterPromptRulesTest {

    private val passage =
        "The Continental System was Napoleon's attempt to defeat Britain economically " +
            "rather than militarily. By closing European ports to British goods, he hoped " +
            "to bankrupt the nation he could not invade. It failed, and it failed for a " +
            "reason that would repeat throughout the century: smuggling was more " +
            "profitable than compliance, and Napoleon's own allies were among the worst " +
            "offenders."

    private val passages = listOf(passage)

    private fun raw(
        prompt: String,
        answer: String,
        quote: String = "smuggling was more profitable than compliance",
        passageIndex: Int = 0,
        type: String = RawPrompt.TYPE_QA,
    ) = RawPrompt(passageIndex, prompt, answer, quote, type)

    private fun cloze(prompt: String, answer: String, passageIndex: Int = 0) =
        RawPrompt(passageIndex, prompt, answer, "smuggling was more profitable than compliance", RawPrompt.TYPE_CLOZE)

    @Test
    fun `a grounded question survives`() {
        assertNull(
            ChapterPromptRules.check(
                raw("Why did the Continental System fail?", "Smuggling was more profitable than compliance"),
                passages,
            )
        )
    }

    /**
     * The rule the whole thing rests on. A model asked for a supporting quote
     * will happily return a plausible paraphrase, and a paraphrase is exactly
     * what an invented fact looks like.
     */
    @Test
    fun `a paraphrased quote is not a quote`() {
        assertEquals(
            PromptRejection.QUOTE_NOT_IN_PASSAGE,
            ChapterPromptRules.check(
                raw(
                    "Why did the Continental System fail?",
                    "Smuggling paid better",
                    quote = "smuggling proved more lucrative than obedience",
                ),
                passages,
            )
        )
    }

    @Test
    fun `reflowed whitespace and curly quotes are still the same sentence`() {
        // What the model must not change is a word. Everything else about how
        // it retypes the line is not evidence of invention.
        assertTrue(
            ChapterPromptRules.containsQuote(
                passage,
                "Napoleon’s   own allies\nwere among the worst offenders",
            )
        )
    }

    @Test
    fun `an excerpt marked with ellipses is honest quoting`() {
        assertTrue(
            ChapterPromptRules.containsQuote(
                passage,
                "…smuggling was more profitable than compliance…",
            )
        )
    }

    @Test
    fun `a question carrying its own answer is thrown away`() {
        assertEquals(
            PromptRejection.ANSWER_GIVEN_AWAY,
            ChapterPromptRules.check(
                raw(
                    "Why did smuggling beat compliance in the Continental System?",
                    "smuggling beat compliance",
                ),
                passages,
            )
        )
    }

    /**
     * "napoleon" contains "no". A plain substring test would throw away a
     * perfectly good yes/no card for giving itself away, which is the sort of
     * silent, occasional loss that is very hard to notice later.
     */
    @Test
    fun `a short answer hiding inside a longer word is not a giveaway`() {
        assertNull(
            ChapterPromptRules.check(
                raw(
                    "Did Napoleon invade Britain?",
                    "No",
                    quote = "the nation he could not invade",
                ),
                passages,
            )
        )
    }

    @Test
    fun `a question about the text is not a question about the world`() {
        // Worthless the moment the book is closed, which is precisely when the
        // card comes back.
        assertEquals(
            PromptRejection.ABOUT_THE_TEXT,
            ChapterPromptRules.check(
                raw("What does this passage say about the blockade?", "That it failed"),
                passages,
            )
        )
    }

    @Test
    fun `a statement is not a prompt but an instruction is`() {
        assertEquals(
            PromptRejection.NOT_A_QUESTION,
            ChapterPromptRules.check(
                raw("The Continental System was a blockade.", "Yes"),
                passages,
            )
        )
        assertNull(
            ChapterPromptRules.check(
                raw("Name the reason the Continental System collapsed.", "Smuggling paid better"),
                passages,
            )
        )
    }

    @Test
    fun `an answer nobody could recall is not an answer`() {
        val essay = List(ChapterPromptRules.MAX_ANSWER_WORDS + 1) { "word" }.joinToString(" ")
        assertEquals(
            PromptRejection.TOO_LONG,
            ChapterPromptRules.check(raw("Why did it fail?", essay), passages),
        )
    }

    @Test
    fun `a prompt citing a passage that was never supplied is thrown away`() {
        assertEquals(
            PromptRejection.UNKNOWN_PASSAGE,
            ChapterPromptRules.check(
                raw("Why did it fail?", "Smuggling", passageIndex = 7),
                passages,
            )
        )
    }

    @Test
    fun `nothing to ask is not a prompt`() {
        assertEquals(
            PromptRejection.EMPTY,
            ChapterPromptRules.check(raw("   ", "Smuggling"), passages),
        )
        assertEquals(
            PromptRejection.EMPTY,
            ChapterPromptRules.check(raw("Why did it fail?", "  "), passages),
        )
    }

    @Test
    fun `the same question from two passages is one card`() {
        val verdict = ChapterPromptRules.sift(
            listOf(
                raw("Why did the Continental System fail?", "Smuggling paid better"),
                raw("Why did the Continental System FAIL?", "Smuggling was profitable"),
            ),
            passages,
        )
        assertEquals(1, verdict.accepted.size)
        assertEquals(listOf(PromptRejection.DUPLICATE), verdict.rejected.map { it.second })
    }

    @Test
    fun `a batch keeps the good and names why it dropped the rest`() {
        val verdict = ChapterPromptRules.sift(
            listOf(
                raw("Why did the Continental System fail?", "Smuggling paid better"),
                raw("What did it do?", "Blockade", quote = "an invented line"),
                raw("What does the author argue about the blockade?", "That it failed"),
            ),
            passages,
        )
        assertEquals(1, verdict.accepted.size)
        assertEquals("Why did the Continental System fail?", verdict.accepted.single().prompt)
        // Each rejection names its own reason, so a batch that mostly failed
        // can say why rather than just coming back short.
        assertEquals(
            listOf(PromptRejection.QUOTE_NOT_IN_PASSAGE, PromptRejection.ABOUT_THE_TEXT),
            verdict.rejected.map { it.second },
        )
    }

    // ---- Wozniak: avoid sets and enumerations --------------------------------

    /**
     * Partial knowledge of a set cannot be graded honestly. The reader
     * half-remembers, grades themselves in the middle, and every fact in the
     * set gets an interval that suits none of them.
     */
    @Test
    fun `a prompt asking for a set is thrown away`() {
        listOf(
            "Name the three causes of the collapse.",
            "List the stages of the blockade.",
            "What are the factors behind the failure?",
            "Name all the ports involved.",
            "Which of the following ports were closed?",
        ).forEach { question ->
            assertEquals(
                "Should have been rejected as a set: $question",
                PromptRejection.ASKS_FOR_A_SET,
                ChapterPromptRules.check(raw(question, "Smuggling"), passages),
            )
        }
    }

    /**
     * The other direction, which matters more: a rule that costs a good card
     * every time it misfires has to be narrower than the intuition behind it.
     */
    @Test
    fun `single-answer prompts are not mistaken for sets`() {
        listOf(
            "Name the reason the Continental System collapsed.",
            "Why did the Continental System fail?",
            "What was Napoleon trying to achieve?",
            "Describe the effect of closing European ports.",
        ).forEach { question ->
            assertNull(
                "Should have survived: $question",
                ChapterPromptRules.check(raw(question, "Smuggling paid better"), passages),
            )
        }
    }

    // ---- Wozniak: cloze deletion --------------------------------------------

    /**
     * The strongest check in the pipeline. A cloze IS the sentence, so filling
     * the deletion back in must reproduce text literally in the book — a model
     * cannot invent a fact and survive it.
     */
    @Test
    fun `a cloze whose sentence rebuilds to the passage survives`() {
        assertNull(
            ChapterPromptRules.check(
                cloze(
                    "smuggling was more profitable than {{c1::compliance}}",
                    "compliance",
                ),
                passages,
            )
        )
    }

    @Test
    fun `a cloze over a sentence that is not in the book is thrown away`() {
        assertEquals(
            PromptRejection.CLOZE_NOT_IN_PASSAGE,
            ChapterPromptRules.check(
                cloze("smuggling was more lucrative than {{c1::obedience}}", "obedience"),
                passages,
            )
        )
    }

    @Test
    fun `a cloze with nothing deleted is not a cloze`() {
        assertEquals(
            PromptRejection.CLOZE_MALFORMED,
            ChapterPromptRules.check(
                cloze("smuggling was more profitable than compliance", "compliance"),
                passages,
            )
        )
    }

    @Test
    fun `deleting the whole sentence leaves nothing to remember it from`() {
        assertEquals(
            PromptRejection.CLOZE_MALFORMED,
            ChapterPromptRules.check(
                cloze(
                    "{{c1::smuggling was more profitable than compliance}}",
                    "smuggling was more profitable than compliance",
                ),
                passages,
            )
        )
    }

    @Test
    fun `the stated answer must be what was actually deleted`() {
        // Otherwise the review screen shows one thing and grades another.
        assertEquals(
            PromptRejection.CLOZE_MALFORMED,
            ChapterPromptRules.check(
                cloze("smuggling was more profitable than {{c1::compliance}}", "Britain"),
                passages,
            )
        )
    }

    @Test
    fun `a cloze is not judged by the question rules`() {
        // It has no question mark and asserts rather than asks, which is
        // exactly what a cloze is supposed to do.
        val verdict = ChapterPromptRules.check(
            cloze("smuggling was more profitable than {{c1::compliance}}", "compliance"),
            passages,
        )
        assertNull(verdict)
    }
}

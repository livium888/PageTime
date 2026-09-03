package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word explanation is worth having only if it is the one thing a dictionary
 * cannot give: the sense used in the sentence on screen. These pin the parts
 * that make that true — the passage reaches the model, the answer is bounded,
 * and the sentence the reader can check it against is rebuilt correctly.
 */
class WordGlossTest {

    private val before = "The senate voted to lift the embargo. Whether to "
    private val term = "sanction"
    private val after = " the treaty divided the chamber for a decade after."

    @Test
    fun `the prompt carries the passage and marks the selection`() {
        val prompt = WordGloss.prompt(term, before, after, "A Book")

        assertTrue("The passage must reach the model", prompt.contains("divided the chamber"))
        assertTrue("The selection must be marked", prompt.contains("⟦sanction⟧"))
        assertTrue(prompt.contains("A Book"))
    }

    @Test
    fun `the prompt rules out the answers a small model invents`() {
        val prompt = WordGloss.prompt(term, before, after, "A Book")

        // Etymology and origins are recalled facts, unbounded by the passage,
        // and the reader cannot catch a wrong one.
        assertTrue(prompt.contains("Never give etymology"))
        assertTrue("A list of senses is what a dictionary already does", prompt.contains("Never list several senses"))
    }

    @Test
    fun `the sentence shown to the reader is rebuilt around the term`() {
        val sentence = WordGloss.sentenceAround(before, term, after)

        assertTrue(sentence.startsWith("Whether to sanction the treaty"))
        assertTrue("The sentence stops at its full stop", sentence.endsWith("decade after."))
        assertFalse("The previous sentence is not part of it", sentence.contains("embargo"))
    }

    @Test
    fun `a whole paragraph is a capture, not a term`() {
        assertNotNull(WordGloss.termProblem("word ".repeat(60)))
        assertNotNull(WordGloss.termProblem("   "))
        assertNull(WordGloss.termProblem("sanction"))
        assertNull(WordGloss.termProblem("the categorical imperative"))
    }

    @Test
    fun `a chatty opening is not part of the answer`() {
        assertEquals(
            "It means to formally approve the treaty.",
            WordGloss.cleanGloss("Sure! It means to formally approve the treaty.")
        )
        assertEquals(
            "It means to formally approve.",
            WordGloss.cleanGloss("In this passage, it means to formally approve.")
        )
    }

    @Test
    fun `a rambling answer is cut at a sentence end`() {
        val long = ("This is a full sentence that says something. ".repeat(20))
        val cleaned = WordGloss.cleanGloss(long)!!

        assertTrue("Kept ${cleaned.length} chars", cleaned.length <= WordGloss.MAX_GLOSS_CHARS)
        assertTrue("A cut answer should still read as finished", cleaned.endsWith("."))
    }

    @Test
    fun `an empty answer is no answer`() {
        assertNull(WordGloss.cleanGloss(""))
        assertNull(WordGloss.cleanGloss("   \n  "))
        assertNull(WordGloss.cleanGloss("Sure!"))
    }

    @Test
    fun `code fences and stray quotes are stripped`() {
        assertEquals(
            "It means to formally approve.",
            WordGloss.cleanGloss("```\"It means to formally approve.\"```")
        )
    }
}

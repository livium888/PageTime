package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word lookup has to serve two readers: someone who has never met the word,
 * and someone who knows it but not which sense is on the page. These pin both
 * answers, the passage reaching the model, and the fallbacks that keep a reply
 * useful when the model does not format it the way it was asked to.
 */
class WordGlossTest {

    private val before = "The senate voted to lift the embargo. Whether to "
    private val term = "sanction"
    private val after = " the treaty divided the chamber for a decade after."

    private val reply =
        """{"kind":"verb","meaning":"To officially allow or approve something.",""" +
            """"here":"Whether to formally approve the treaty.",""" +
            """"example":"The school sanctioned a trip to the museum."}"""

    @Test
    fun `the prompt carries the passage and marks the selection`() {
        val prompt = WordGloss.prompt(term, before, after, "A Book")

        assertTrue("The passage must reach the model", prompt.contains("divided the chamber"))
        assertTrue("The selection must be marked", prompt.contains("⟦sanction⟧"))
        assertTrue(prompt.contains("A Book"))
    }

    @Test
    fun `the prompt asks for a plain meaning a learner can read`() {
        val prompt = WordGloss.prompt(term, before, after, "A Book")

        // The reader who taps a word often does not know it at all, so the
        // general meaning is asked for first and in the simplest words.
        assertTrue(prompt.contains("still\n") || prompt.contains("learning English"))
        assertTrue(prompt.contains("must not need a second dictionary"))
        assertTrue("A learner needs the part of speech", prompt.contains("kind:"))
        assertTrue("And an example to fix it in place", prompt.contains("example:"))
    }

    @Test
    fun `the prompt still rules out what a small model invents`() {
        val prompt = WordGloss.prompt(term, before, after, "A Book")

        assertTrue(prompt.contains("Never give etymology"))
        assertTrue(prompt.contains("Never list several senses"))
    }

    @Test
    fun `all four fields are read back`() {
        val parts = WordGloss.parse(reply, "sanction")!!

        assertEquals("verb", parts.kind)
        assertEquals("To officially allow or approve something.", parts.meaning)
        assertEquals("Whether to formally approve the treaty.", parts.here)
        assertEquals("The school sanctioned a trip to the museum.", parts.example)
        assertFalse(parts.isEmpty)
    }

    @Test
    fun `a reply cut off mid-object keeps what arrived`() {
        val parts = WordGloss.parse(
            """{"kind":"verb","meaning":"To officially allow or approve something.","here":"Whether to""",
            "sanction"
        )!!

        assertEquals("verb", parts.kind)
        assertEquals("To officially allow or approve something.", parts.meaning)
        assertNotNull(parts.here)
    }

    @Test
    fun `a model that answers in prose still answers`() {
        // Dropping a real answer over a formatting problem would leave the
        // reader with nothing.
        val parts = WordGloss.parse("It means to officially approve something.", "sanction")!!

        assertEquals("It means to officially approve something.", parts.meaning)
        assertNull(parts.kind)
    }

    @Test
    fun `a chatty opening is not part of the answer`() {
        val parts = WordGloss.parse("""{"meaning":"Sure! It means to approve."}""", "sanction")!!
        assertEquals("It means to approve.", parts.meaning)
    }

    @Test
    fun `an answer stripped of its lead-in still starts like a sentence`() {
        val parts = WordGloss.parse("""{"meaning":"In this passage, it means to approve."}""", "sanction")!!
        assertEquals("It means to approve.", parts.meaning)
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
    fun `a rambling field is cut at a sentence end`() {
        val long = "This is a full sentence that says something. ".repeat(20)
        val parts = WordGloss.parse("""{"meaning":"$long"}""", "sanction")!!

        assertTrue("Kept ${parts.meaning!!.length}", parts.meaning!!.length <= WordGloss.MAX_FIELD_CHARS)
        assertTrue("A cut answer should still read as finished", parts.meaning!!.endsWith("."))
    }

    @Test
    fun `an empty reply is no answer`() {
        assertNull(WordGloss.parse("", "sanction"))
        assertNull(WordGloss.parse("   \n  ", "sanction"))
        assertTrue(WordGloss.parse("""{"meaning":""}""", "sanction")?.isEmpty ?: true)
    }

    @Test
    fun `a code fence is not part of the answer`() {
        val fenced = "```json\n" + """{"meaning":"To approve."}""" + "\n```"
        assertEquals("To approve.", WordGloss.parse(fenced, "sanction")!!.meaning)
    }

    @Test
    fun `an example that never uses the word is dropped`() {
        // The failure this model actually makes: by the last field the nearest
        // text is the explanation it just wrote, so it continues from that and
        // produces a sentence built out of the definition, with the word itself
        // nowhere in it.
        val parts = WordGloss.parse(
            """{"kind":"verb","meaning":"To officially allow something.",
               |"here":"To approve the treaty.",
               |"example":"The school officially allowed a trip to the museum."}""".trimMargin(),
            "sanction"
        )!!
        assertNull("An example of the meaning is not an example of the word", parts.example)
        assertEquals("To officially allow something.", parts.meaning)
    }

    @Test
    fun `an example using an inflected form is kept`() {
        assertTrue(WordGloss.usesTerm("The school sanctioned a trip.", "sanction"))
        assertTrue(WordGloss.usesTerm("They are sanctioning the plan.", "sanction"))
        assertTrue(WordGloss.usesTerm("He runs every morning.", "run"))
    }

    @Test
    fun `a different word that merely starts the same is not the word`() {
        // "sanctify" shares six letters with "sanction" and is a different
        // word. A prefix rule that only compared the first few characters
        // would accept it.
        assertFalse(WordGloss.usesTerm("The priest sanctified the water.", "sanction"))
        assertFalse(WordGloss.usesTerm("She was very polite about it.", "politician"))
        // The word has to start a word, not sit inside one: "fact" is not
        // an example of "act".
        assertFalse(WordGloss.usesTerm("The fact remains unchanged.", "act"))
        assertTrue(WordGloss.usesTerm("She will act on it today.", "act"))
    }

    @Test
    fun `a phrase is judged on the word carrying its meaning`() {
        assertTrue(WordGloss.usesTerm("Their well-being came first.", "well-being"))
        assertTrue(WordGloss.usesTerm("He acted in good faith throughout.", "in good faith"))
        assertFalse(WordGloss.usesTerm("He was honest throughout.", "in good faith"))
    }

    @Test
    fun `punctuation dragged in with the selection comes off the word`() {
        assertEquals("sanction", WordGloss.cleanTerm("\u201Csanction,\u201D"))
        assertEquals("sanction", WordGloss.cleanTerm("  sanction.  "))
        assertEquals("well-being", WordGloss.cleanTerm("(well-being)"))
        // A selection that is only punctuation is nothing to look up.
        assertNotNull(WordGloss.termProblem("\u201C \u201D"))
    }

    @Test
    fun `the prompt names the word in the example rule`() {
        // Naming it once in a general instruction was not enough; the rule and
        // the reply skeleton both carry the actual word now.
        val prompt = WordGloss.prompt("sanction", "the council voted to ", " the library", "A Book")
        assertTrue(prompt.contains("has to contain the word \"sanction\""))
        assertTrue(prompt.contains("sentence using sanction"))
    }
}

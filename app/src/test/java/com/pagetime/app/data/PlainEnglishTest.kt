package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainEnglishTest {

    @Test
    fun `a full reply becomes a rewrite and its hard words`() {
        val parts = PlainEnglish.parse(
            """{"plain": "The weather was bad. Even so, the group went on.",
               |"words": "notwithstanding = even so; inclement = bad, stormy"}""".trimMargin()
        )
        assertNotNull(parts)
        requireNotNull(parts)
        assertTrue(parts.plain.startsWith("The weather"))
        assertTrue(parts.words!!.contains("inclement"))
    }

    @Test
    fun `an empty words field takes no room rather than showing a blank heading`() {
        val parts = requireNotNull(
            PlainEnglish.parse("""{"plain": "The weather was bad.", "words": ""}""")
        )
        assertNull(parts.words)
    }

    @Test
    fun `a reply shaped like the answer but with no rewrite is refused`() {
        // The failure the word lookup shipped once: a present-but-empty field
        // read as an answer, and the reader was handed JSON punctuation.
        assertNull(PlainEnglish.parse("""{"plain": "", "words": "a = b"}"""))
        assertNull(PlainEnglish.parse("{}"))
        assertNull(PlainEnglish.parse("   "))
    }

    @Test
    fun `a bare sentence with no JSON is still a rewrite`() {
        // Unlike the word lookup, prose here is safe to accept. Asked to say a
        // sentence more simply, a model that replies with a plain sentence has
        // done the job — there is no claim in it that could be invented.
        val parts = requireNotNull(PlainEnglish.parse("The weather was bad, so they waited."))
        assertEquals("The weather was bad, so they waited.", parts.plain)
        assertNull(parts.words)
    }

    @Test
    fun `a reply cut off mid-rewrite keeps what arrived`() {
        val parts = requireNotNull(
            PlainEnglish.parse("""{"plain": "The weather was bad and the group decided that they""")
        )
        assertTrue(parts.plain.startsWith("The weather was bad"))
    }

    @Test
    fun `a word is sent to the lookup and half a page is sent to capture`() {
        assertNull(PlainEnglish.passageProblem("The weather was thoroughly inclement that day."))
        assertNotNull(PlainEnglish.passageProblem(""))
        assertNotNull(PlainEnglish.passageProblem("inclement"))
        assertNotNull(PlainEnglish.passageProblem("word ".repeat(400)))
    }

    @Test
    fun `the widest rewrite prompt fits the token budget`() {
        val prompt = PlainEnglish.prompt(
            "word ".repeat(PlainEnglish.MAX_PASSAGE_CHARS),
            "A Reasonably Long Book Title"
        )
        val tokens = LlmTokenBudget.estimateTokens(prompt)
        val budget = LlmTokenBudget.inputBudget(PlainEnglish.REPLY_TOKENS)
        assertTrue("Rewrite prompt needs ~$tokens tokens, budget is $budget", tokens <= budget)
    }

    @Test
    fun `both answers reach the sheet with their labels already in order`() {
        val plain = PlainReading(
            passage = "Notwithstanding the inclement weather, the expedition proceeded.",
            parts = PlainParts(plain = "The weather was bad. They went anyway.", words = null),
            source = LlmProviderKind.OFFLINE,
        ).asAnswer()
        assertEquals(listOf("SAYS"), plain.parts.map { it.label })
        assertTrue("The original has to stay in view", plain.quoted.contains("Notwithstanding"))

        val word = Gloss(
            term = "sanction",
            sentence = "The council voted to sanction the new library.",
            parts = GlossParts(kind = "verb", meaning = "To allow.", here = null, example = "Yes."),
            source = LlmProviderKind.OFFLINE,
        ).asAnswer()
        assertEquals(listOf("MEANS", "FOR EXAMPLE"), word.parts.map { it.label })
        assertEquals("verb", word.badge)
    }
}

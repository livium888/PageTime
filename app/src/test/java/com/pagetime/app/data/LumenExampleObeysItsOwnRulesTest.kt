package com.pagetime.app.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worked example in the capture prompt, held to the rules printed three
 * lines above it.
 *
 * This exists because the previous example broke them. Its front was a
 * rewording of its own passage's second sentence, and it reused four words
 * from that passage under a rule saying never to copy a phrase. A small model
 * imitates a demonstration far more readily than it follows prose, so the
 * example was teaching exactly the behaviour the rules forbid — and the cards
 * that came back were paraphrases, which is what it had been shown.
 *
 * An example that breaks the rules is worse than no example, and nothing about
 * it fails at runtime. Hence a test.
 */
class LumenExampleObeysItsOwnRulesTest {

    private val card =
        requireNotNull(LumenCapture.parseDraft(LumenAiPrompts.EXAMPLE_CARD)) {
            "The example card must parse with the same parser the model's reply goes through"
        }

    private val front get() = card.first
    private val back get() = card.second

    @Test
    fun `the example parses as a card`() {
        assertNotNull(LumenCapture.parseDraft(LumenAiPrompts.EXAMPLE_CARD))
    }

    @Test
    fun `the example front respects the word cap it states`() {
        val words = front.trim().split(Regex("\\s+")).size
        assertTrue(
            "Example front is $words words, over the stated cap of " +
                "${LumenCapture.MAX_FRONT_WORDS}: $front",
            words <= LumenCapture.MAX_FRONT_WORDS,
        )
    }

    @Test
    fun `the example never points at its source`() {
        assertTrue("Example front reports on the source: $front", !LumenCapture.mentionsSource(front))
        assertTrue("Example back reports on the source: $back", !LumenCapture.mentionsSource(back))
    }

    @Test
    fun `the example back would not be flagged as thin`() {
        assertNull(LumenCapture.backProblem(front, back))
    }

    @Test
    fun `the example does not echo its own passage`() {
        assertTrue(
            !LumenCapture.isPassageEcho(front, LumenAiPrompts.EXAMPLE_PASSAGE)
        )
    }

    /**
     * REMOVED: an assertion that the example's front shares no content word
     * with its passage.
     *
     * It encoded a hypothesis — that the example taught rewording because it
     * reused the passage's words — and the hypothesis was measured and found
     * false. An example with zero overlap made cards vaguer, not sharper: for
     * a 1B model, abstraction and vagueness are the same direction, and shared
     * vocabulary appears to anchor it to real content rather than to teach it
     * to copy.
     *
     * A test that asserts a belief rather than a requirement is a trap for
     * whoever tries the next experiment, so it is gone rather than inverted.
     * The structural checks below stand on their own: they describe what a
     * card must BE, not what anyone thinks makes one good.
     */

    /** The example has to reach the model, or none of the above matters. */
    @Test
    fun `the example is actually in the built-in prompt`() {
        assertTrue(LumenAiPrompts.DEFAULT_CARD_TEMPLATE.contains(LumenAiPrompts.EXAMPLE_PASSAGE))
        assertTrue(LumenAiPrompts.DEFAULT_CARD_TEMPLATE.contains(LumenAiPrompts.EXAMPLE_CARD))
    }
}

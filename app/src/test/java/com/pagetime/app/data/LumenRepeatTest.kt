package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture window is a fixed radius around the reading position and far
 * wider than a phone page, so two captures a page apart are handed passages
 * that overlap heavily. The model then names the same idea twice, which is not
 * a second card. These pin what counts as the same idea and the promise that
 * noticing it never costs the reader the card they got.
 */
class LumenRepeatTest {

    private val filed = listOf("Fiction lets strangers cooperate")
    private val twoSentences =
        "Shared stories let people who have never met act as one group. " +
            "That is why a myth can hold a nation together where kinship cannot."

    private fun ok(text: String) = Result.success(LlmResult(text, LlmProviderKind.OFFLINE))

    @Test
    fun `the same claim word for word is a repeat`() {
        assertEquals(
            "Fiction lets strangers cooperate",
            LumenCapture.repeatOf("Fiction lets strangers cooperate.", filed)
        )
    }

    @Test
    fun `a reworded version of a filed claim is a repeat`() {
        assertNotNull(LumenCapture.repeatOf("Fiction lets strangers cooperate at scale", filed))
    }

    @Test
    fun `a different idea from the same passage is not a repeat`() {
        assertNull(LumenCapture.repeatOf("Myths outlive the people who tell them", filed))
    }

    @Test
    fun `an empty box repeats nothing`() {
        assertNull(LumenCapture.repeatOf("Fiction lets strangers cooperate", emptyList()))
    }

    @Test
    fun `a repeat is re-asked and a genuinely new idea wins`() = runTest {
        val prompts = mutableListOf<String>()
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            prompts += request.prompt
            if (prompts.size == 1) {
                ok("""{"front":"Fiction lets strangers cooperate","back":"$twoSentences"}""")
            } else {
                ok("""{"front":"Myths outlive the people who tell them","back":"$twoSentences"}""")
            }
        }

        val outcome =
            LumenLocalDraft.generate(
                call = call,
                passage = "A passage about shared stories.",
                bookTitle = "Sapiens",
                alreadyFiled = filed,
            )

        assertEquals("Myths outlive the people who tell them", outcome.card!!.first)
        assertEquals(2, outcome.attempts)
        assertNull(outcome.repeatOf)
        assertTrue(
            "The retry must name the idea to avoid, or it returns the same one",
            prompts[1].contains("Fiction lets strangers cooperate")
        )
    }

    @Test
    fun `a second repeat never loses the first card`() = runTest {
        // A passage can genuinely hold one idea. Saying so beats manufacturing
        // a worse second card, and beats silently filing the duplicate.
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            ok("""{"front":"Fiction lets strangers cooperate","back":"$twoSentences"}""")
        }

        val outcome =
            LumenLocalDraft.generate(
                call = call,
                passage = "A passage about shared stories.",
                bookTitle = "Sapiens",
                alreadyFiled = filed,
            )

        assertEquals("Fiction lets strangers cooperate", outcome.card!!.first)
        assertEquals("Fiction lets strangers cooperate", outcome.repeatOf)
        assertNull("The note itself is fine; only the idea repeats", outcome.backProblem)
    }

    @Test
    fun `a fresh idea needs no retry`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            calls++
            ok("""{"front":"Myths outlive the people who tell them","back":"$twoSentences"}""")
        }

        val outcome =
            LumenLocalDraft.generate(
                call = call,
                passage = "A passage about shared stories.",
                bookTitle = "Sapiens",
                alreadyFiled = filed,
            )

        assertEquals(1, calls)
        assertEquals(1, outcome.attempts)
        assertNull(outcome.repeatOf)
    }
}

package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The front had a word cap and an echo check and came good; the back had
 * neither, so anything the model returned counted as a finished note. These
 * pin the bar the back now has to clear, and the promise that enforcing it
 * never costs the reader a card.
 */
class LumenBackQualityTest {

    private val front = "Fiction lets strangers cooperate"
    private val twoSentences =
        "Shared stories let people who have never met act as one group. " +
            "That is why a myth can hold a nation together where kinship cannot."

    private fun ok(text: String) = Result.success(LlmResult(text, LlmProviderKind.OFFLINE))

    @Test
    fun `a two sentence explanation holds up`() {
        assertNull(LumenCapture.backProblem(front, twoSentences))
    }

    @Test
    fun `an empty note is missing, not merely short`() {
        assertEquals(LumenCapture.BackProblem.MISSING, LumenCapture.backProblem(front, "   "))
    }

    @Test
    fun `a note cut off mid-thought is a fragment`() {
        assertEquals(
            LumenCapture.BackProblem.FRAGMENT,
            LumenCapture.backProblem(front, "Shared stories let people who have never met")
        )
        assertEquals(
            LumenCapture.BackProblem.FRAGMENT,
            LumenCapture.backProblem(front, "Too short.")
        )
    }

    @Test
    fun `a note that only repeats the title is caught`() {
        assertEquals(
            LumenCapture.BackProblem.RESTATES_FRONT,
            LumenCapture.backProblem(front, "Fiction lets strangers cooperate.")
        )
    }

    @Test
    fun `a single sentence is flagged for another ask`() {
        assertEquals(
            LumenCapture.BackProblem.SINGLE_SENTENCE,
            LumenCapture.backProblem(front, "Shared stories let strangers act as one group together.")
        )
    }

    @Test
    fun `an abbreviation does not read as a sentence break`() {
        // Over-counting only costs a retry; under-counting would re-ask about
        // notes that were already fine, so the count leans generous.
        assertTrue(LumenCapture.sentenceCount("Dr. Smith argued this for years.") >= 2)
    }

    @Test
    fun `a thin back is re-asked and the better answer wins`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            calls++
            if (calls == 1) {
                ok("""{"front":"$front","back":"Shared stories bind people together somehow."}""")
            } else {
                ok("""{"front":"$front","back":"$twoSentences"}""")
            }
        }

        val outcome = LumenLocalDraft.generate(call, "A passage about cooperation.", "Sapiens")

        assertEquals(twoSentences, outcome.card!!.second)
        assertEquals(2, outcome.attempts)
        assertNull("The kept card's note holds up", outcome.backProblem)
    }

    @Test
    fun `a retry that is no better never loses the first card`() = runTest {
        // Re-asking must be free of downside, or it becomes a gamble on a card
        // the reader already had.
        val firstBack = "Shared stories bind large groups of strangers together."
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            calls++
            if (calls == 1) {
                ok("""{"front":"$front","back":"$firstBack"}""")
            } else {
                ok("""not a card at all""")
            }
        }

        val outcome = LumenLocalDraft.generate(call, "A passage about cooperation.", "Sapiens")

        assertEquals(firstBack, outcome.card!!.second)
        assertEquals(LumenCapture.BackProblem.SINGLE_SENTENCE, outcome.backProblem)
    }
}

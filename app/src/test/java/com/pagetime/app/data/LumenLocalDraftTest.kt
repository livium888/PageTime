package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-core tests for the on-device capture driver and echo guard. */
class LumenLocalDraftTest {

    private val passage =
        "However, this arena is extraordinarily large, allowing Sapiens to play an " +
            "astounding variety of games. Thanks to their ability to invent fiction, " +
            "Sapiens create more and more complex games."

    private fun ok(text: String) = Result.success(LlmResult(text, LlmProviderKind.OFFLINE))

    @Test
    fun `reports the prompt actually sent, so diagnostics cannot drift from it`() = runTest {
        // The capture log records the token cost from this callback. Reporting
        // anything but the real prompt is how a constant passageLength=120 hid
        // an over-budget prompt through days of diagnosis.
        var reported: String? = null
        var sent: String? = null
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            sent = request.prompt
            ok("""{"front":"Fiction bonds large groups","back":"Shared stories let many cooperate."}""")
        }

        LumenLocalDraft.generate(
            call = call,
            passage = passage,
            bookTitle = "Sapiens",
            onPromptBuilt = { reported = it },
        )

        assertEquals(sent, reported)
        assertTrue(reported!!.contains(passage.take(40)))
    }

    @Test
    fun `the reply budget leaves room for the prompt`() = runTest {
        var request: LlmRequest? = null
        val call: suspend (LlmRequest) -> Result<LlmResult> = { r ->
            request = r
            ok("""{"front":"A title","back":"A note."}""")
        }

        LumenLocalDraft.generate(call, passage, "Sapiens")

        assertEquals(LumenLocalDraft.REPLY_TOKENS, request!!.maxOutputTokens)
        assertTrue(
            "The reply budget must leave input room inside the engine's total",
            LlmTokenBudget.inputBudget(LumenLocalDraft.REPLY_TOKENS) > 0
        )
    }

    @Test
    fun `uses a good reply and primes the model with the passage`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            calls++
            assertTrue(request.prompt.contains("Passage:"))
            ok("""{"front":"Fiction bonds large groups","back":"Shared stories let many people cooperate."}""")
        }

        val outcome = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertEquals("Fiction bonds large groups", outcome.card!!.first)
        assertEquals("A usable first reply needs no retry", 1, calls)
        assertEquals(1, outcome.attempts)
    }

    @Test
    fun `a dud first reply is re-asked with the stricter prompt`() = runTest {
        // The whole point of the retry: a small model's first answer is often a
        // copy of the passage, and the second, starker ask usually lands.
        val prompts = mutableListOf<String>()
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            prompts += request.prompt
            if (prompts.size == 1) {
                ok("""{"front": "However, this arena is extraordinarily large", "back": "copy"}""")
            } else {
                ok("""{"front":"Fiction lets strangers cooperate","back":"Shared stories bind large groups."}""")
            }
        }

        val outcome = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertEquals("Fiction lets strangers cooperate", outcome.card!!.first)
        assertEquals(2, outcome.attempts)
        assertNull("A landed card carries no rejection", outcome.rejection)
        assertEquals(2, prompts.size)
        assertTrue("The retry must use the stricter prompt", prompts[0] != prompts[1])
    }

    @Test
    fun `reports the passage echo when both attempts copy the passage`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            calls++
            ok("""Here is your card: {"front": "However, this arena is extraordinarily large", "back": "copy"}""")
        }

        val outcome = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertNull(outcome.card)
        assertEquals(LumenLocalDraft.Rejection.PASSAGE_ECHO, outcome.rejection)
        assertEquals(2, calls)
    }

    @Test
    fun `reports no reply when the provider call fails`() = runTest {
        val call: suspend (LlmRequest) -> Result<LlmResult> = {
            Result.failure(IllegalStateException("runtime exploded"))
        }

        val outcome = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertNull(outcome.card)
        assertEquals(LumenLocalDraft.Rejection.NO_REPLY, outcome.rejection)
    }

    @Test
    fun `logs the discarded reply for diagnosis`() = runTest {
        val logged = mutableListOf<String>()
        val call: suspend (LlmRequest) -> Result<LlmResult> = {
            ok("""{"front":"However, this arena is extraordinarily large","back":"echo"}""")
        }

        val outcome =
            LumenLocalDraft.generate(call, passage, "Sapiens", debugLog = { logged += it })

        assertNull(outcome.card)
        assertEquals("Both attempts are logged", 2, logged.size)
        assertTrue(logged.all { it.startsWith("discarded local draft") })
    }
}

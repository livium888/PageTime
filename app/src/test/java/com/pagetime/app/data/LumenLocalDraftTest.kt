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

        val parsed = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertEquals("Fiction bonds large groups", parsed!!.first)
        assertEquals(1, calls)
    }

    @Test
    fun `rejects a passage echo without performing another native inference`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            calls++
            ok("""Here is your card: {"front": "However, this arena is extraordinarily large", "back": "copy"}""")
        }

        val parsed = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertNull(parsed)
        assertEquals(1, calls)
    }

    @Test
    fun `returns null when a provider call fails`() = runTest {
        val call: suspend (LlmRequest) -> Result<LlmResult> = {
            Result.failure(IllegalStateException("runtime exploded"))
        }

        assertNull(LumenLocalDraft.generate(call, passage, "Sapiens"))
    }

    @Test
    fun `logs the discarded reply for diagnosis`() = runTest {
        val logged = mutableListOf<String>()
        val call: suspend (LlmRequest) -> Result<LlmResult> = {
            ok("""{"front":"However, this arena is extraordinarily large","back":"echo"}""")
        }

        assertNull(
            LumenLocalDraft.generate(call, passage, "Sapiens", debugLog = { logged += it })
        )
        assertEquals(1, logged.size)
        assertTrue(logged.single().startsWith("discarded local draft"))
    }
}

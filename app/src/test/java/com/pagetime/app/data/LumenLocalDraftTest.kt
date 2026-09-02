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

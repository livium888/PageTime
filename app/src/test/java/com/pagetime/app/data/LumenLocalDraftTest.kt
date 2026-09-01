package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-core tests for the on-device capture driver: two attempts, echo guard. */
class LumenLocalDraftTest {

    private val passage =
        "However, this arena is extraordinarily large, allowing Sapiens to play an " +
            "astounding variety of games. Thanks to their ability to invent fiction, " +
            "Sapiens create more and more complex games."

    private fun ok(text: String) = Result.success(LlmResult(text, LlmProviderKind.OFFLINE))

    @Test
    fun `uses a good first reply without retrying`() = runTest {
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
    fun `retries with the strict prompt when the first reply is a passage echo`() = runTest {
        val prompts = mutableListOf<LlmRequest>()
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { request ->
            prompts += request
            val out =
                if (calls++ == 0) {
                    // The model copied the passage instead of answering.
                    """Here is your card: {"front": "However, this arena is extraordinarily large", "back": "copy"}"""
                } else {
                    """{"front": "Fiction bonds large groups", "back": "Shared stories let many people cooperate toward common goals."}"""
                }
            ok(out)
        }

        val parsed = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertEquals("Fiction bonds large groups", parsed!!.first)
        assertEquals(2, calls)
        assertTrue(prompts[1].prompt != prompts[0].prompt)
        assertTrue(prompts[1].prompt.contains("Never copy"))
    }

    @Test
    fun `retries when the first reply does not parse`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            val out =
                if (calls++ == 0) {
                    // Truncated mid-object, no labels, nothing usable.
                    "Here is your card. The passage is about how fiction lets large groups cooperate."
                } else {
                    """{"front":"Fiction unites strangers","back":"Stories create shared goals across large groups."}"""
                }
            ok(out)
        }

        val parsed = LumenLocalDraft.generate(call, passage, "Sapiens")

        assertEquals("Fiction unites strangers", parsed!!.first)
        assertEquals(2, calls)
    }

    @Test
    fun `falls back when a provider call fails`() = runTest {
        val call: suspend (LlmRequest) -> Result<LlmResult> = {
            Result.failure(IllegalStateException("runtime exploded"))
        }

        assertNull(LumenLocalDraft.generate(call, passage, "Sapiens"))
    }

    @Test
    fun `falls back when both attempts are unusable`() = runTest {
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            calls++
            ok("no json at all, just prose about the passage")
        }

        assertNull(LumenLocalDraft.generate(call, passage, "Sapiens"))
        assertEquals(2, calls)
    }

    @Test
    fun `logs the discarded reply for diagnosis`() = runTest {
        val logged = mutableListOf<String>()
        var calls = 0
        val call: suspend (LlmRequest) -> Result<LlmResult> = { _ ->
            val out =
                if (calls++ == 0) {
                    """{"front":"However, this arena is extraordinarily large","back":"echo"}"""
                } else {
                    """{"front":"Fiction unites strangers","back":"Stories create shared goals."}"""
                }
            ok(out)
        }

        val parsed = LumenLocalDraft.generate(call, passage, "Sapiens", debugLog = { logged += it })

        assertEquals("Fiction unites strangers", parsed!!.first)
        assertEquals(1, logged.size)
        assertTrue(logged.single().startsWith("discarded local draft"))
    }
}

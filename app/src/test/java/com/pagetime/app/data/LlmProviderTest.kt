package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProviderTest {
    @Test
    fun `unknown provider keys safely default to Gemini`() {
        assertEquals(LlmProviderKind.GEMINI, LlmProviderKind.fromKey(null))
        assertEquals(LlmProviderKind.GEMINI, LlmProviderKind.fromKey("unknown"))
    }

    @Test
    fun `all supported provider keys round trip`() {
        LlmProviderKind.entries.forEach { provider ->
            assertEquals(provider, LlmProviderKind.fromKey(provider.key))
        }
    }

    @Test
    fun `offline provider fails clearly until model is installed`() =
        runTest {
            val provider = OfflineLlmProvider()
            assertFalse(provider.isAvailable)
            val result = provider.generate(LlmRequest("make a card"))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("No offline model"))
        }

    @Test
    fun `router sends offline mode to the local model when installed`() {
        assertEquals(
            LumenDraftSource.LOCAL,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.OFFLINE,
                geminiConfigured = true,
                localModelAvailable = true,
            ),
        )
    }

    @Test
    fun `router never sends offline mode to Gemini when the model is missing`() {
        assertEquals(
            LumenDraftSource.FALLBACK,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.OFFLINE,
                geminiConfigured = true,
                localModelAvailable = false,
            ),
        )
    }

    @Test
    fun `router keeps Gemini mode on Gemini when a key exists`() {
        assertEquals(
            LumenDraftSource.GEMINI,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.GEMINI,
                geminiConfigured = true,
                localModelAvailable = false,
            ),
        )
    }

    @Test
    fun `router falls back when Gemini is chosen but no key exists`() {
        assertEquals(
            LumenDraftSource.FALLBACK,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.GEMINI,
                geminiConfigured = false,
                localModelAvailable = false,
            ),
        )
    }

    @Test
    fun `ask every time prefers Gemini and falls back to local when unconfigured`() {
        assertEquals(
            LumenDraftSource.GEMINI,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.ASK_EVERY_TIME,
                geminiConfigured = true,
                localModelAvailable = true,
            ),
        )
        assertEquals(
            LumenDraftSource.LOCAL,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.ASK_EVERY_TIME,
                geminiConfigured = false,
                localModelAvailable = true,
            ),
        )
        assertEquals(
            LumenDraftSource.FALLBACK,
            LumenDraftRouter.sourceFor(
                LlmProviderKind.ASK_EVERY_TIME,
                geminiConfigured = false,
                localModelAvailable = false,
            ),
        )
    }
}

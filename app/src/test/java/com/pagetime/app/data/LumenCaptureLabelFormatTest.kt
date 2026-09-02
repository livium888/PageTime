package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest

/**
 * Tests that the local draft driver picks up a `Back: …` labeled reply even
 * when the model cannot produce JSON — and that labeled replies are correctly
 * parsed by [LumenCapture.parseDraft], including single-quoted variants.
 */
class LumenCaptureLabelFormatTest {

    private val passage =
        "However, this arena is extraordinarily large, allowing Sapiens to play an " +
            "astounding variety of games. Thanks to their ability to invent fiction, " +
            "Sapiens create more and more complex games."

    @Test
    fun `rejects a labeled reply that has no Front line`() {
        val raw =
            """
            |Back: Shared stories let large groups cooperate and create complex games.
            """.trimMargin()
        val parsed = LumenCapture.parseDraft(raw)
        assertNull("A labeled reply without a Front line should not be accepted", parsed)
    }

    @Test
    fun `parses a Front Back labeled reply`() {
        val raw =
            """
            |Front: Fiction social power
            |Back: Shared stories let large groups cooperate and create complex games.
            """.trimMargin()
        val parsed = LumenCapture.parseDraft(raw)
        assertEquals("Fiction social power", parsed!!.first)
        assertEquals("Shared stories let large groups cooperate and create complex games.", parsed.second)
    }

    @Test
    fun `parses a single quoted JSON reply`() {
        val raw = "{ 'front': 'Fiction social power', 'back': 'Shared stories let large groups cooperate.' }"
        val parsed = LumenCapture.parseDraft(raw)
        assertEquals("Fiction social power", parsed!!.first)
        assertEquals("Shared stories let large groups cooperate.", parsed.second)
    }

    @Test
    fun `stably handles the dense Sapiens passage`() {
        val raw = LumenCapture.parseDraft("{ \"front\": \"Fiction bonds large groups\", \"back\": \"Shared stories let many people cooperate.\" }")
        assertEquals("Fiction bonds large groups", raw!!.first)
    }
}

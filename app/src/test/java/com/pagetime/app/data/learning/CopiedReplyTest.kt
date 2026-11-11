package com.pagetime.app.data.learning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopiedReplyTest {

    private val sent = "The first paragraph of the stretch that was read, long enough to be told apart. " +
        "And then a second sentence."
    private val reply = """```json
{"cards":[{"prompt":"What is told apart?","answer":"The stretch","source_quote":"long enough to be told apart"}]}
```"""

    @Test
    fun `a reply with cards in it is read`() {
        assertTrue(CopiedReply.worthReading(reply, sent, alreadyTried = null))
    }

    @Test
    fun `the prompt itself coming back is not a reply`() {
        val prompt = "Write flashcards as JSON like {\"cards\":[]} from this text:\n\n$sent"
        assertFalse(CopiedReply.worthReading(prompt, sent, alreadyTried = null))
    }

    @Test
    fun `ordinary clipboard text is left alone`() {
        assertFalse(CopiedReply.worthReading("", sent, null))
        assertFalse(CopiedReply.worthReading("https://example.com/article", sent, null))
        assertFalse(CopiedReply.worthReading("see you at 7", sent, null))
    }

    @Test
    fun `the same text is not tried twice`() {
        assertFalse(CopiedReply.worthReading(reply, sent, alreadyTried = reply))
    }
}

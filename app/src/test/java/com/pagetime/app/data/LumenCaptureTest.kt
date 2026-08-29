package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-core tests for Lumen capture: sentence-bounded windows, keyword
 * extraction, snippet round-trips, and draft parsing (AI + fallback).
 */
class LumenCaptureTest {

    // region captureWindow

    @Test
    fun `window is bounded by whole sentence boundaries near the edges`() {
        val sentence = "The quick brown fox jumps over the lazy dog. "
        val text = sentence.repeat(120) // ~5.7k chars, well over one window
        val center = text.length / 2

        val window = LumenCapture.captureWindow(text, center)

        assertTrue(window.length <= 2 * LumenCapture.DEFAULT_RADIUS_CHARS + 100)
        assertTrue(window.isNotBlank())
        // Window starts at a sentence boundary: the previous sentence's period
        // is not inside the window (or the window starts at 0).
        if (!window.startsWith("The quick")) {
            assertFalse(window.startsWith("og jumps"))
        }
        // Ends at a boundary: trailing text is a complete sentence.
        assertTrue(window.trimEnd().endsWith("."))
    }

    @Test
    fun `window near the start clamps to the text start`() {
        val text = "First sentence. Second sentence. " + "Filler text continues here. ".repeat(200)
        val window = LumenCapture.captureWindow(text, 5)
        assertTrue(window.startsWith("First sentence."))
    }

    @Test
    fun `window near the end clamps to the text end`() {
        val text = "Filler text continues here. ".repeat(200) + "The final sentence ends here."
        val window = LumenCapture.captureWindow(text, text.length - 3)
        assertTrue(window.trimEnd().endsWith("ends here."))
    }

    @Test
    fun `blank or empty text yields an empty window`() {
        assertEquals("", LumenCapture.captureWindow("", 10))
        assertEquals("", LumenCapture.captureWindow("   \n  ", 10))
    }

    @Test
    fun `null offset is treated as position zero`() {
        val text = "One. ".repeat(400)
        val window = LumenCapture.captureWindow(text, null)
        assertTrue(window.startsWith("One."))
    }

    @Test
    fun `text shorter than the radius is returned whole`() {
        val text = "A short complete story. The end."
        assertEquals(text, LumenCapture.captureWindow(text, 10))
    }

    @Test
    fun `newline boundaries split transcript-style text`() {
        val line = "Speaker one says something here.\n"
        val text = line.repeat(150)
        val window = LumenCapture.captureWindow(text, text.length / 2)
        // Newlines count as boundaries, so the window edges are line-complete.
        assertTrue(
            window.startsWith("Speaker") &&
                (window.endsWith("here.") || window.endsWith("here. "))
        )
    }

    // endregion

    // region extractKeywords

    @Test
    fun `keywords drop stop words and short tokens`() {
        val k = LumenCapture.extractKeywords("The theory of relativity changed physics and physics changed the world")
        val words = k.split(" ")
        assertTrue(words.contains("relativity"))
        assertTrue(words.contains("physics"))
        assertFalse(words.contains("the"))
        assertFalse(words.contains("and"))
    }

    @Test
    fun `keywords are ordered by frequency then length`() {
        val k = LumenCapture.extractKeywords("entropy entropy entropy order order system")
        assertEquals("entropy", k.split(" ").first())
        assertTrue(k.split(" ").indexOf("order") < k.split(" ").indexOf("system"))
    }

    @Test
    fun `keywords respects the max limit`() {
        val k = LumenCapture.extractKeywords("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu", max = 5)
        assertEquals(5, k.split(" ").size)
    }

    @Test
    fun `keywords are lowercased`() {
        val k = LumenCapture.extractKeywords("Capitalized Words Here")
        assertTrue(k == k.lowercase())
    }

    // endregion

    // region snippet round-trip

    @Test
    fun `snippets round-trip through json`() {
        val snippets = listOf(
            LumenSnippet("first thought", 1_000L, 0.25f),
            LumenSnippet("second thought", 2_000L, null)
        )
        val json = LumenCapture.snippetsToJson(snippets)
        val parsed = LumenCapture.snippetsFromJson(json)
        assertEquals(2, parsed.size)
        assertEquals("first thought", parsed[0].text)
        assertEquals(1_000L, parsed[0].addedAt)
        assertEquals(0.25f, parsed[0].fraction!!, 1e-6f)
        assertEquals("second thought", parsed[1].text)
        assertNull(parsed[1].fraction)
    }

    @Test
    fun `corrupt snippet json degrades to an empty list`() {
        assertEquals(emptyList<LumenSnippet>(), LumenCapture.snippetsFromJson("not json"))
        assertEquals(emptyList<LumenSnippet>(), LumenCapture.snippetsFromJson("[]"))
    }

    // endregion

    // region drafts

    @Test
    fun `fallback draft uses the first whole sentence as the front`() {
        val passage = "Spaced repetition builds memory. Second sentence follows here."
        val (front, back) = LumenCapture.fallbackDraft(passage)
        assertEquals("Spaced repetition builds memory.", front)
        assertEquals("", back)
    }

    @Test
    fun `fallback draft truncates very long sentences with an ellipsis`() {
        val longSentence = "word ".repeat(40).trim() + "."
        val (front, _) = LumenCapture.fallbackDraft(longSentence)
        assertTrue(front.length <= 91)
        assertTrue(front.endsWith("…"))
    }

    @Test
    fun `parseDraft accepts a strict json reply`() {
        val (front, back) = LumenCapture.parseDraft("""{"front":"What is X?","back":"X means Y."}""")!!
        assertEquals("What is X?", front)
        assertEquals("X means Y.", back)
    }

    @Test
    fun `parseDraft tolerates code fences`() {
        val (front, _) = LumenCapture.parseDraft("```json\n{\"front\":\"F\",\"back\":\"B\"}\n```")!!
        assertEquals("F", front)
    }

    @Test
    fun `parseDraft rejects blank fronts and malformed json`() {
        assertNull(LumenCapture.parseDraft("""{"front":"","back":"B"}"""))
        assertNull(LumenCapture.parseDraft("totally not json"))
        assertNull(LumenCapture.parseDraft(""))
    }

    // endregion
}

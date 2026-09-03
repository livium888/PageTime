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
    fun `advancing the reading position yields a different captured window and front`() {
        // The EPUB capture regression: capture must center on the CURRENT page,
        // not freeze on a chapter-tail window. Two different positions within a
        // long chapter must produce two different source windows AND two
        // different card fronts — otherwise "read more, same card".
        val text = buildString {
            for (i in 0 until 400) append("Sentence number $i holds some distinct words here. ")
        }
        val early = LumenCapture.captureWindow(text, text.length / 4)
        val later = LumenCapture.captureWindow(text, text.length * 3 / 4)
        assertTrue(early != later)
        val earlyFront = LumenCapture.fallbackDraft(early).first
        val laterFront = LumenCapture.fallbackDraft(later).first
        assertTrue(earlyFront != laterFront)
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

    @Test
    fun `parseDraft finds json buried in surrounding prose`() {
        val raw =
            "Here is your card: " +
                """{"front":"Spaced repetition","back":"Reviews spaced over time stick better."}""" +
                " I hope this helps!"
        val (front, back) = LumenCapture.parseDraft(raw)!!
        assertEquals("Spaced repetition", front)
        assertEquals("Reviews spaced over time stick better.", back)
    }

    @Test
    fun `parseDraft accepts a plain front back label format`() {
        val raw = "Front: Spaced repetition\nBack: Reviews spaced over time stick better."
        val (front, back) = LumenCapture.parseDraft(raw)!!
        assertEquals("Spaced repetition", front)
        assertEquals("Reviews spaced over time stick better.", back)
    }

    @Test
    fun `parseDraft strips quotes and emphasis from fields`() {
        val (front, back) =
            LumenCapture.parseDraft("""{"front":"\"Spaced repetition\"","back":"**Reviews stick better.**"}""")!!
        assertEquals("Spaced repetition", front)
        assertEquals("Reviews stick better.", back)
    }

    @Test
    fun `parseDraft caps a runaway front to a claim`() {
        // The prompt asks for at most eight words and a small model treats that
        // as a suggestion, so the cap is enforced on the way in.
        val longFront = "word ".repeat(40).trim()

        val (front, _) = LumenCapture.parseDraft("""{"front":"$longFront","back":"B"}""")!!

        assertEquals(LumenCapture.MAX_FRONT_WORDS, front.split(" ").size)
        assertTrue("A word-boundary cut needs no ellipsis", !front.endsWith("…"))
    }

    @Test
    fun `a front cut keeps the opening clause when there is one`() {
        val rambling =
            "Fiction lets strangers cooperate, because shared stories bind large groups together"

        assertEquals("Fiction lets strangers cooperate", LumenCapture.trimFront(rambling))
    }

    @Test
    fun `a front cut falls back to the word count without a clause boundary`() {
        val rambling = "Shared stories quietly bind very large groups of complete strangers together"

        val front = LumenCapture.trimFront(rambling)

        assertEquals(LumenCapture.MAX_FRONT_WORDS, front.split(" ").size)
        assertTrue(rambling.startsWith(front))
    }

    @Test
    fun `a front already short enough is left alone`() {
        val claim = "Fiction lets strangers cooperate"

        assertEquals(claim, LumenCapture.trimFront(claim))
    }

    @Test
    fun `parseDraft salvages a json reply truncated mid-back`() {
        val raw = """{"front":"Spaced repetition","back":"Reviews spaced over time stick"""
        val (front, back) = LumenCapture.parseDraft(raw)!!
        assertEquals("Spaced repetition", front)
        assertEquals("Reviews spaced over time stick", back)
    }

    @Test
    fun `parseDraft salvages a json reply cut right after the front`() {
        val raw = """{"front":"Spaced repetition"""
        val (front, back) = LumenCapture.parseDraft(raw)!!
        assertEquals("Spaced repetition", front)
        assertEquals("", back)
    }

    @Test
    fun `parseDraft salvages single-quoted json`() {
        val raw = """{'front': 'Spaced repetition', 'back': 'Reviews stick better.'}"""
        val (front, back) = LumenCapture.parseDraft(raw)!!
        assertEquals("Spaced repetition", front)
        assertEquals("Reviews stick better.", back)
    }

    @Test
    fun `parseDraft unescapes quotes inside salvaged fields`() {
        val raw = """{"front":"Spaced repetition","back":"It is the \"spacing effect\" at work."}"""
        val (_, back) = LumenCapture.parseDraft(raw)!!
        assertEquals("It is the \"spacing effect\" at work.", back)
    }

    @Test
    fun `isPassageEcho flags a verbatim copied front`() {
        val passage =
            "However, this arena is extraordinarily large, allowing Sapiens to play an astounding variety of games."
        assertTrue(
            LumenCapture.isPassageEcho(
                "However, this arena is extraordinarily large",
                passage,
            )
        )
    }

    @Test
    fun `isPassageEcho ignores case and line breaks in the passage`() {
        val passage = "First line.\nHowever, this arena is extraordinarily\nlarge, allowing Sapiens to play."
        assertTrue(LumenCapture.isPassageEcho("this arena is extraordinarily large", passage))
    }

    @Test
    fun `isPassageEcho accepts a paraphrased front`() {
        val passage =
            "Everything you see in this table has a strong scientific evidence that it will help with sleep."
        assertFalse(
            LumenCapture.isPassageEcho(
                "Surprising sleep interventions backed by strong scientific evidence",
                passage,
            )
        )
    }

    @Test
    fun `isPassageEcho ignores short quoted terms`() {
        val passage = "The mitochondria is the powerhouse of the cell."
        assertFalse(LumenCapture.isPassageEcho("the powerhouse", passage))
    }

    // endregion
}

package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture ends where the reader points and runs back whole paragraphs.
 *
 * The character window this replaced measured outwards from a scroll position,
 * so a passage began mid-clause, ended mid-clause, and reached back a distance
 * that meant nothing to anyone reading it. Worse, the distance was fixed: two
 * captures a page apart overlapped almost entirely, which is why the model kept
 * naming the same idea twice.
 */
class LumenParagraphCaptureTest {

    private fun para(tag: String, words: Int) = "$tag " + "word ".repeat(words).trim()

    private val p1 = para("ALPHA", 40)
    private val p2 = para("BETA", 40)
    private val p3 = para("GAMMA", 40)
    private val p4 = para("DELTA", 40)
    private val chapter = listOf(p1, p2, p3, p4).joinToString(LumenCapture.PARAGRAPH_BREAK)

    @Test
    fun `a passage ends at the paragraph pointed at and takes two before it`() {
        val passage = LumenCapture.paragraphPassage(chapter, chapter.length)
        assertTrue("starts at a paragraph start", passage.startsWith("BETA"))
        assertTrue("ends at a paragraph end", passage.endsWith("word"))
        assertTrue("the first paragraph is not reached back to", !passage.contains("ALPHA"))
    }

    @Test
    fun `pointing at an earlier paragraph gives an earlier passage`() {
        // The whole reason for the change: two captures at different places are
        // different passages. The fixed window made them almost the same text.
        val early = LumenCapture.paragraphPassage(chapter, chapter.indexOf("BETA") + 5)
        val late = LumenCapture.paragraphPassage(chapter, chapter.length)
        assertTrue(early.contains("ALPHA"))
        assertTrue(!early.contains("DELTA"))
        assertTrue(late.contains("DELTA"))
        assertTrue(!late.contains("ALPHA"))
    }

    @Test
    fun `the first paragraph of a chapter is a passage on its own`() {
        val passage = LumenCapture.paragraphPassage(chapter, chapter.indexOf("ALPHA") + 5)
        assertEquals(p1, passage)
    }

    @Test
    fun `an anchor in the gap belongs to the paragraph that just ended`() {
        // Pointing between two paragraphs means the one the reader finished,
        // not the one they have not started.
        val gap = chapter.indexOf("GAMMA") - 1
        assertTrue(LumenCapture.paragraphPassage(chapter, gap).endsWith(p2.takeLast(20)))
    }

    @Test
    fun `three lines of dialogue are not an idea, so it keeps pulling`() {
        // Three paragraphs is the rule; a floor of characters is why it is not
        // the whole rule. Dialogue runs to one-line exchanges.
        val dialogue = (1..10).joinToString(LumenCapture.PARAGRAPH_BREAK) { "“Line $it.”" }
        val passage = LumenCapture.paragraphPassage(dialogue, dialogue.length)
        val taken = passage.split(LumenCapture.PARAGRAPH_BREAK).size
        assertTrue("took more than the nominal three, got $taken", taken > 3)
        assertTrue(
            "never more than the hard ceiling, got $taken",
            taken <= LumenCapture.HARD_MAX_CAPTURE_PARAGRAPHS
        )
    }

    @Test
    fun `long paragraphs stop at three`() {
        val long = (1..8).joinToString(LumenCapture.PARAGRAPH_BREAK) { para("P$it", 60) }
        val passage = LumenCapture.paragraphPassage(long, long.length)
        assertEquals(
            LumenCapture.MAX_CAPTURE_PARAGRAPHS,
            passage.split(LumenCapture.PARAGRAPH_BREAK).size
        )
    }

    @Test
    fun `text with no paragraph breaks is still capturable`() {
        // A chapter with no block markup falls back to one flat run. It has to
        // stay usable rather than returning nothing.
        val flat = "One long run of text with no breaks in it at all."
        assertEquals(flat, LumenCapture.paragraphPassage(flat, flat.length))
    }

    @Test
    fun `blank text captures nothing`() {
        assertEquals("", LumenCapture.paragraphPassage("", 0))
        assertEquals("", LumenCapture.paragraphPassage("   \n\n  ", 3))
    }

    @Test
    fun `an anchor past the end lands on the last paragraph`() {
        val passage = LumenCapture.paragraphPassage(chapter, chapter.length * 2)
        assertTrue(passage.endsWith(p4.takeLast(20)))
    }
}

package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture ends where the reader points and runs back whole paragraphs.
 *
 * How far back is decided by how much text there is, not by a count of
 * paragraphs. Count was the first rule and it was the wrong unit: a paragraph
 * is anything from a line of dialogue to nine hundred words, so three of them
 * is either nothing to think about or more than the model can hold — and which
 * one depends on the book, which a number of paragraphs cannot know.
 *
 * Paragraphs remain what gets ADDED, because cutting anywhere else puts back
 * the ragged half-sentence edges the whole change exists to remove.
 */
class LumenParagraphCaptureTest {

    /** Roughly [words] words, so a paragraph's length is what is being varied. */
    private fun para(tag: String, words: Int) = "$tag " + "word ".repeat(words).trim()

    private fun chapterOf(count: Int, words: Int) =
        (1..count).joinToString(LumenCapture.PARAGRAPH_BREAK) { para("P$it", words) }

    private fun paragraphsIn(passage: String) =
        passage.split(LumenCapture.PARAGRAPH_BREAK).size

    @Test
    fun `ordinary prose reaches back about as far as a reader would expect`() {
        val chapter = chapterOf(count = 8, words = 70)
        val passage = LumenCapture.paragraphPassage(chapter, chapter.length)
        assertTrue("ends at the paragraph pointed at", passage.endsWith("word"))
        assertTrue("starts at a paragraph start", passage.startsWith("P"))
        assertTrue(
            "reaches the target, got ${passage.length}",
            passage.length >= LumenCapture.PASSAGE_TARGET_CHARS - 200
        )
    }

    @Test
    fun `one enormous paragraph stands alone rather than being cut`() {
        // A dense paragraph can run to nine hundred words. Three of those would
        // bury the idea; half of one would have the ragged edge this exists to
        // remove. So it is taken whole and by itself.
        val chapter = chapterOf(count = 3, words = 900)
        val passage = LumenCapture.paragraphPassage(chapter, chapter.length)
        assertEquals(1, paragraphsIn(passage))
        assertTrue(passage.startsWith("P3"))
    }

    @Test
    fun `a short paragraph does not drag an enormous one in behind it`() {
        // Where the ceiling earns its keep: the anchor is far short of the
        // target, so the rule wants more — but the only thing behind it is a
        // wall of text that would swamp the passage. Better a short passage
        // than one the idea is lost inside.
        val chapter = para("P1", 500) + LumenCapture.PARAGRAPH_BREAK + para("P2", 15)
        val passage = LumenCapture.paragraphPassage(chapter, chapter.length)
        assertEquals(1, paragraphsIn(passage))
        assertTrue(passage.startsWith("P2"))
        assertTrue(
            "under the target and that is correct, got ${passage.length}",
            passage.length < LumenCapture.PASSAGE_TARGET_CHARS
        )
    }

    @Test
    fun `one-line paragraphs are capped by count, not chased forever`() {
        // Dialogue, verse, a list. The character target would swallow a page of
        // exchanges and be a character window again with extra steps.
        val dialogue = (1..20).joinToString(LumenCapture.PARAGRAPH_BREAK) { "“Line $it.”" }
        val passage = LumenCapture.paragraphPassage(dialogue, dialogue.length)
        assertEquals(LumenCapture.MAX_CAPTURE_PARAGRAPHS, paragraphsIn(passage))
    }

    @Test
    fun `pointing at an earlier paragraph gives an earlier passage`() {
        // The reason for all of it: two captures at different places are
        // different passages. A fixed character window made them near-identical,
        // which is why the model kept naming the same idea twice.
        val chapter = chapterOf(count = 8, words = 70)
        val early = LumenCapture.paragraphPassage(chapter, chapter.indexOf("P3") + 5)
        val late = LumenCapture.paragraphPassage(chapter, chapter.length)
        assertTrue(early.contains("P3"))
        assertTrue("an early capture cannot contain the last paragraph", !early.contains("P8"))
        assertTrue(late.contains("P8"))
        assertTrue("a late capture cannot contain the third", !late.contains("P3 "))
    }

    @Test
    fun `the first paragraph of a chapter is a passage on its own`() {
        val chapter = chapterOf(count = 8, words = 70)
        val passage = LumenCapture.paragraphPassage(chapter, 5)
        assertEquals(1, paragraphsIn(passage))
        assertTrue(passage.startsWith("P1"))
    }

    @Test
    fun `an anchor in the gap belongs to the paragraph that just ended`() {
        // Pointing between two paragraphs means the one just finished, not the
        // one not yet started.
        val chapter = chapterOf(count = 8, words = 70)
        val gap = chapter.indexOf("P5") - 1
        assertTrue(LumenCapture.paragraphPassage(chapter, gap).endsWith("word"))
        assertTrue(!LumenCapture.paragraphPassage(chapter, gap).contains("P5 "))
    }

    @Test
    fun `an anchor past the end lands on the last paragraph`() {
        val chapter = chapterOf(count = 8, words = 70)
        val passage = LumenCapture.paragraphPassage(chapter, chapter.length * 2)
        assertTrue(passage.contains("P8"))
    }

    @Test
    fun `text with no paragraph breaks is still capturable`() {
        // A chapter with no block markup falls back to one flat run, and has to
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
    fun `a chapter that will not split is cut at the reader, not handed over whole`() {
        // The real failure this guards. A book whose markup produced no
        // recognised blocks came back as one 99,589-character "paragraph", and
        // the whole chapter was returned as the passage — after which the
        // trimmer kept its middle and the model was asked about text from
        // halfway through the file.
        val flat = "word ".repeat(20_000).trim()
        val at = flat.length / 2
        val passage = LumenCapture.paragraphPassage(flat, at)

        assertTrue(
            "must not return the whole chapter, got ${passage.length}",
            passage.length < flat.length / 2
        )
        assertTrue(
            "stays within the ceiling, got ${passage.length}",
            passage.length <= LumenCapture.PASSAGE_CEILING_CHARS
        )
        assertTrue(
            "ends where the reader is",
            flat.substring(0, at).endsWith(passage.takeLast(20))
        )
    }

    @Test
    fun `a short unsplittable chapter is still returned whole`() {
        // The fallback is for chapters too big to hand over, not for every
        // chapter without paragraph markup.
        val flat = "One long run of text with no breaks in it at all."
        assertEquals(flat, LumenCapture.paragraphPassage(flat, flat.length))
    }
}

package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSpanTest {

    /** Six paragraphs of ten words each, labelled so a slice can be read at a glance. */
    private fun chapter(label: String): String =
        (1..6).joinToString("\n\n") { p ->
            "$label$p " + (1..9).joinToString(" ") { "w$it" }
        }

    private val chapters = listOf(chapter("A"), chapter("B"), chapter("C"))
    private val text: (Int) -> String = { chapters[it] }

    private fun pointAt(chapter: Int, paragraph: Int): SpanPoint {
        // The middle of paragraph `paragraph` (1-based) of that chapter.
        val raw = chapters[chapter]
        val starts = raw.split("\n\n").runningFold(0) { acc, p -> acc + p.length + 2 }
        val mid = starts[paragraph - 1] + raw.split("\n\n")[paragraph - 1].length / 2
        return SpanPoint(chapter, mid.toFloat() / raw.length)
    }

    private fun labels(slice: ReadingSpan.Slice): List<String> =
        Regex("""[A-C][1-6]""").findAll(slice.text).map { it.value }.toList()

    // --- Within one chapter ---

    @Test
    fun `a span starts and ends on whole paragraphs`() {
        val s = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 4))
        assertEquals(listOf("A2", "A3", "A4"), labels(s))
        assertTrue(s.text.startsWith("A2 "))
        assertTrue(s.text.endsWith("w9"))
    }

    @Test
    fun `the same position gives the one paragraph it is in`() {
        val s = ReadingSpan.slice(text, 3, pointAt(0, 3), pointAt(0, 3))
        assertEquals(listOf("A3"), labels(s))
    }

    @Test
    fun `a current position before the start is no span at all`() {
        val s = ReadingSpan.slice(text, 3, pointAt(0, 5), pointAt(0, 2))
        assertTrue(s.backwards)
        assertTrue(s.isEmpty)
    }

    // --- Across chapters ---

    @Test
    fun `a span crossing chapters takes the tail of the first, all between, and the head of the last`() {
        val s = ReadingSpan.slice(text, 3, pointAt(0, 5), pointAt(2, 2))
        assertEquals(listOf("A5", "A6", "B1", "B2", "B3", "B4", "B5", "B6", "C1", "C2"), labels(s))
    }

    @Test
    fun `only the chapters in the span are read`() {
        val read = mutableListOf<Int>()
        ReadingSpan.slice({ read += it; chapters[it] }, 3, pointAt(1, 2), pointAt(1, 4))
        assertEquals(listOf(1), read)
    }

    @Test
    fun `a blank chapter in the middle is skipped`() {
        val withBlank = listOf(chapters[0], "   ", chapters[2])
        val s = ReadingSpan.slice({ withBlank[it] }, 3, pointAt(0, 6), pointAt(2, 1))
        assertEquals(listOf("A6", "C1"), labels(s))
    }

    @Test
    fun `no chapters is an empty span`() {
        assertTrue(ReadingSpan.slice(text, 0, SpanPoint(0, 0f), SpanPoint(0, 1f)).isEmpty)
    }

    @Test
    fun `positions past the last chapter are clamped to it`() {
        val s = ReadingSpan.slice(text, 3, pointAt(2, 5), SpanPoint(9, 1f))
        assertEquals(listOf("C5", "C6"), labels(s))
    }

    // --- Chapters with no paragraph breaks (e.g. converted from PDF) ---

    @Test
    fun `a chapter that is one huge paragraph still yields a small span`() {
        val sentences = (1..1000).joinToString(" ") { "Sentence$it has a few plain words." }
        val one = "CHAPTER 1 " + sentences
        val s = ReadingSpan.slice({ one }, 1, SpanPoint(0, 0.50f), SpanPoint(0, 0.51f))
        assertTrue("got ${s.words} words", s.words in 40..600)
        assertFalse(s.text.contains("Sentence1 "))
    }

    @Test
    fun `unpunctuated unbroken text is also cut`() {
        val one = (1..5000).joinToString(" ") { "w$it" }
        val s = ReadingSpan.slice({ one }, 1, SpanPoint(0, 0.5f), SpanPoint(0, 0.5f))
        assertTrue("got ${s.words} words", s.words < 600)
    }

    @Test
    fun `normal paragraphs are left exactly as they were`() {
        assertEquals(chapters[0], ReadingSpan.breakUpLongParagraphs(chapters[0]))
    }

    @Test
    fun `breaking up paragraphs loses no words`() {
        val one = (1..1000).joinToString(" ") { "Sentence$it ends here." }
        assertEquals(
            ReadingSpan.words(one),
            ReadingSpan.words(ReadingSpan.breakUpLongParagraphs(one)),
        )
    }

    // --- Nudging the end, because an EPUB position is only approximate ---

    @Test
    fun `the end can be moved forward a paragraph at a time`() {
        val base = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 3))
        val more = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 3), endAdjust = 2)
        assertEquals(listOf("A2", "A3"), labels(base))
        assertEquals(listOf("A2", "A3", "A4", "A5"), labels(more))
    }

    @Test
    fun `the end can be pulled back but never past the start`() {
        val less = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 4), endAdjust = -1)
        assertEquals(listOf("A2", "A3"), labels(less))
        val floor = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 4), endAdjust = -50)
        assertEquals(listOf("A2"), labels(floor))
    }

    @Test
    fun `moving the end forward stops at the end of the chapter`() {
        val s = ReadingSpan.slice(text, 3, pointAt(0, 5), pointAt(0, 5), endAdjust = 40)
        assertEquals(listOf("A5", "A6"), labels(s))
    }

    // --- Sizes and previews ---

    @Test
    fun `words are counted and the edges are previewed`() {
        val s = ReadingSpan.slice(text, 3, pointAt(0, 1), pointAt(0, 2))
        assertEquals(20, s.words)
        assertEquals("A1 w1 w2 w3 w4 w5 w6 w7 …", s.firstWords)
        assertEquals("… w2 w3 w4 w5 w6 w7 w8 w9", s.lastWords)
        assertTrue(s.lastWords.startsWith("… "))
        assertTrue(s.lastWords.endsWith("w9"))
    }

    @Test
    fun `a short span is flagged and a long one is warned about`() {
        assertTrue(ReadingSpan.slice(text, 3, pointAt(0, 1), pointAt(0, 1)).tooShort)
        val long = "word ".repeat(ReadingSpan.WARN_WORDS + 1)
        val s = ReadingSpan.slice({ long }, 1, SpanPoint(0, 0f), SpanPoint(0, 1f))
        assertTrue(s.long)
        assertFalse(s.tooLong)
        val huge = "word ".repeat(ReadingSpan.MAX_WORDS + 1)
        assertTrue(ReadingSpan.slice({ huge }, 1, SpanPoint(0, 0f), SpanPoint(0, 1f)).tooLong)
    }

    // --- Handing over to the next span, so a paragraph is never taken twice ---

    @Test
    fun `the next span starts exactly where this one ended without repeating its last paragraph`() {
        val first = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 4))
        assertEquals(listOf("A2", "A3", "A4"), labels(first))
        val second = ReadingSpan.slice(text, 3, first.next!!, pointAt(0, 6))
        assertEquals(listOf("A5", "A6"), labels(second))
    }

    @Test
    fun `a span ending at the end of a chapter hands over to the start of the next`() {
        val first = ReadingSpan.slice(text, 3, pointAt(0, 5), SpanPoint(0, 1f))
        assertEquals(listOf("A5", "A6"), labels(first))
        val second = ReadingSpan.slice(text, 3, first.next!!, pointAt(1, 2))
        assertEquals(listOf("B1", "B2"), labels(second))
    }

    @Test
    fun `right after a hand over there is nothing new until the reader moves on`() {
        val first = ReadingSpan.slice(text, 3, pointAt(0, 2), pointAt(0, 4))
        val again = ReadingSpan.slice(text, 3, first.next!!, pointAt(0, 4))
        assertTrue(again.isEmpty)
    }

    @Test
    fun `an empty or backwards span hands over nothing`() {
        assertEquals(null, ReadingSpan.slice(text, 3, pointAt(0, 5), pointAt(0, 2)).next)
        assertEquals(null, ReadingSpan.slice(text, 0, SpanPoint(0, 0f), SpanPoint(0, 1f)).next)
    }

    @Test
    fun `plain text hands over exactly past the last sentence taken`() {
        val first = ReadingSpan.slicePlain(
            prose,
            SpanPoint(0, 0f, textOffset = 0),
            SpanPoint(0, 0f, textOffset = prose.indexOf("longer than")),
        )
        assertTrue(first.text.endsWith("than the first."))
        val second = ReadingSpan.slicePlain(
            prose, first.next!!, SpanPoint(0, 1f, textOffset = prose.length),
        )
        assertTrue(second.text.startsWith("The third closes"))
        assertFalse(second.text.contains("second sentence"))
    }

    // --- Plain text: exact offsets, snapped to sentences ---

    private val prose = "The first sentence is short. The second sentence runs a little longer than the first. " +
        "The third closes the paragraph.\n\nA new paragraph begins here. It has two sentences."

    @Test
    fun `plain text starts at the beginning of the sentence the page began in`() {
        val mid = prose.indexOf("runs a little")
        val s = ReadingSpan.slicePlain(prose, SpanPoint(0, 0f, textOffset = mid), SpanPoint(0, 1f, textOffset = prose.length))
        assertTrue(s.text.startsWith("The second sentence"))
    }

    @Test
    fun `plain text ends at the end of the sentence the page ended in`() {
        val mid = prose.indexOf("longer than")
        val s = ReadingSpan.slicePlain(prose, SpanPoint(0, 0f, textOffset = 0), SpanPoint(0, 0f, textOffset = mid))
        assertTrue(s.text.endsWith("than the first."))
    }

    @Test
    fun `a page that already ends on a sentence does not borrow the next one`() {
        val endOfSecond = prose.indexOf("first. ") + "first.".length
        val s = ReadingSpan.slicePlain(prose, SpanPoint(0, 0f, textOffset = 0), SpanPoint(0, 0f, textOffset = endOfSecond))
        assertTrue(s.text.endsWith("than the first."))
        assertFalse(s.text.contains("third"))
    }

    @Test
    fun `plain text prefers the start of the paragraph over a sentence`() {
        val inSecondParagraph = prose.indexOf("It has two")
        val s = ReadingSpan.slicePlain(prose, SpanPoint(0, 0f, textOffset = inSecondParagraph), SpanPoint(0, 1f, textOffset = prose.length))
        assertTrue(s.text.startsWith("A new paragraph"))
    }

    @Test
    fun `an unbroken block with no punctuation is cut at the offsets themselves`() {
        val block = (1..400).joinToString(" ") { "w$it" }
        val a = block.indexOf("w100")
        val b = block.indexOf("w200")
        val s = ReadingSpan.slicePlain(block, SpanPoint(0, 0f, textOffset = a), SpanPoint(0, 0f, textOffset = b))
        assertTrue(s.text.startsWith("w100"))
        assertTrue(s.text.endsWith("w199"))
    }

    @Test
    fun `plain text with the end before the start is backwards`() {
        val s = ReadingSpan.slicePlain(prose, SpanPoint(0, 0f, textOffset = 80), SpanPoint(0, 0f, textOffset = 10))
        assertTrue(s.backwards)
    }

    // --- Ordering and storage of points ---

    @Test
    fun `points order by chapter then by position`() {
        assertTrue(SpanPoint(1, 0.9f).isBefore(SpanPoint(2, 0.1f)))
        assertTrue(SpanPoint(2, 0.1f).isBefore(SpanPoint(2, 0.2f)))
        assertFalse(SpanPoint(2, 0.2f).isBefore(SpanPoint(2, 0.2f)))
        assertTrue(SpanPoint(0, 0.9f, textOffset = 10).isBefore(SpanPoint(0, 0.1f, textOffset = 20)))
    }

    @Test
    fun `a point survives a round trip through JSON`() {
        val p = SpanPoint(4, 0.25f, textOffset = 1234, chapterTitle = "Chapter 5")
        assertEquals(p, SpanPoint.fromJson(p.toJson()))
        val epub = SpanPoint(2, 0.5f)
        assertEquals(epub, SpanPoint.fromJson(epub.toJson()))
        val handedOver = SpanPoint(2, 0.5f, snapped = true)
        assertEquals(handedOver, SpanPoint.fromJson(handedOver.toJson()))
    }

    // region EPUB anchors: cut at the words the page opens with

    private val anchored = (1..40).joinToString("\n\n") { "Paragraph number $it opens here and then carries on for a while longer." }

    @Test
    fun `an anchor puts the cut on the paragraph the page really opens with`() {
        val target = anchored.indexOf("Paragraph number 20 opens")
        // The fraction is a page out: it points at paragraph 23.
        val estimate = anchored.indexOf("Paragraph number 23 opens")
        assertEquals(target, ReadingSpan.anchorPosition(anchored, "Paragraph number 20 opens here and then", estimate))

        val end = SpanPoint(0, estimate.toFloat() / anchored.length, anchor = "Paragraph number 20 opens here and then")
        val slice = ReadingSpan.slice({ anchored }, 1, SpanPoint(0, 0f), end)
        assertTrue(slice.text.endsWith("Paragraph number 20 opens here and then carries on for a while longer."))
    }

    @Test
    fun `an anchor matches whatever the whitespace`() {
        val position = ReadingSpan.anchorPosition(anchored, "  Paragraph   number 7\nopens here\tand then carries", 0)
        assertEquals(anchored.indexOf("Paragraph number 7 opens"), position)
    }

    @Test
    fun `a short or missing or absent anchor falls back to the fraction`() {
        assertNull(ReadingSpan.anchorPosition(anchored, null, 100))
        assertNull(ReadingSpan.anchorPosition(anchored, "Paragraph", 100))
        assertNull(ReadingSpan.anchorPosition(anchored, "These words are nowhere in the chapter at all", 100))
    }

    @Test
    fun `of repeated words the nearest to the estimate wins`() {
        val text = (1..10).joinToString("\n\n") { "The same sentence again and again and again." }
        val third = text.indexOf("The same", text.indexOf("The same", 1) + 1)
        assertEquals(third, ReadingSpan.anchorPosition(text, "The same sentence again and again", third + 5))
    }

    @Test
    fun `a match far from the estimate is not believed`() {
        // One element holding the whole chapter reports the chapter's opening
        // words on every page.
        val long = "The chapter opens with these particular words. " + "Filler sentence here. ".repeat(600)
        assertNull(ReadingSpan.anchorPosition(long, "The chapter opens with these particular words.", long.length - 50))
    }

    @Test
    fun `an anchor survives being saved`() {
        val point = SpanPoint(3, 0.4f, anchor = "The opening words of the page")
        assertEquals(point, SpanPoint.fromJson(point.toJson()))
    }

    // endregion
}

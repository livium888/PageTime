package com.pagetime.app.data

import com.pagetime.app.data.local.TextHighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHighlightSpansTest {

    private fun txt(
        id: String,
        start: Int,
        end: Int,
        quote: String = "q"
    ) = TextHighlightEntity(
        id = id,
        bookId = "b1",
        kind = "txt",
        startOffset = start,
        endOffset = end,
        quote = quote,
        createdAt = 100L
    )

    private fun epub(id: String) = TextHighlightEntity(
        id = id,
        bookId = "b1",
        kind = "epub",
        startLocatorJson = "{}",
        quote = "q",
        createdAt = 100L
    )

    @Test
    fun `malformed txt spans read as null`() {
        assertNull(TextHighlightSpans.txtRange(txt("neg", -1, 10)))
        assertNull(TextHighlightSpans.txtRange(txt("reversed", 10, 5)))
        assertNull(TextHighlightSpans.txtRange(txt("empty", 5, 5)))
        assertNull(TextHighlightSpans.txtRange(epub("epub")))
    }

    @Test
    fun `a span wholly inside a page clips to itself`() {
        val ranges = TextHighlightSpans.txtPageRanges(
            highlights = listOf(txt("a", 100, 200)),
            pageStartOffset = 0,
            pageEndOffset = 1_000
        )
        assertEquals(listOf(100 to 200), ranges)
    }

    @Test
    fun `a multi-page span is clipped at both page edges`() {
        // Pages 0..500, 500..900, 900..1400. A span 400..1000 touches all three.
        val highlights = listOf(txt("span", 400, 1_000))

        assertEquals(
            listOf(400 to 500),
            TextHighlightSpans.txtPageRanges(highlights, pageStartOffset = 0, pageEndOffset = 500)
        )
        assertEquals(
            listOf(0 to 400), // page-relative: 500..900 is covered 500..900 → 0..400
            TextHighlightSpans.txtPageRanges(highlights, pageStartOffset = 500, pageEndOffset = 900)
        )
        assertEquals(
            listOf(0 to 100),
            TextHighlightSpans.txtPageRanges(highlights, pageStartOffset = 900, pageEndOffset = 1_400)
        )
    }

    @Test
    fun `overlapping highlights merge rather than double-paint`() {
        val highlights = listOf(
            txt("a", 100, 300),
            txt("b", 200, 400)
        )
        val ranges = TextHighlightSpans.txtPageRanges(highlights, pageStartOffset = 0, pageEndOffset = 1_000)
        assertEquals(listOf(100 to 400), ranges)
    }

    @Test
    fun `adjacent highlights stay separate but ordered`() {
        val highlights = listOf(
            txt("a", 100, 200),
            txt("b", 200, 300),
            txt("c", 50, 60)
        )
        val ranges = TextHighlightSpans.txtPageRanges(highlights, pageStartOffset = 0, pageEndOffset = 1_000)
        assertEquals(listOf(50 to 60, 100 to 300), ranges)
    }

    @Test
    fun `a page with no overlap yields nothing`() {
        val ranges = TextHighlightSpans.txtPageRanges(
            highlights = listOf(txt("a", 100, 200)),
            pageStartOffset = 500,
            pageEndOffset = 900
        )
        assertTrue(ranges.isEmpty())
    }

    @Test
    fun `clipToPage maps a whole-book span onto one page`() {
        // The reader's live sentence grab is clipped with the same arithmetic a
        // stored highlight uses, so a sentence spanning a page turn paints on
        // both pages instead of vanishing.
        assertEquals(400 to 500, TextHighlightSpans.clipToPage(400, 1_000, 0, 500))
        assertEquals(0 to 400, TextHighlightSpans.clipToPage(400, 1_000, 500, 900))
        assertEquals(0 to 100, TextHighlightSpans.clipToPage(400, 1_000, 900, 1_400))
    }

    @Test
    fun `clipToPage yields nothing off the page or for an empty span`() {
        assertNull(TextHighlightSpans.clipToPage(100, 200, 500, 900))
        assertNull(TextHighlightSpans.clipToPage(700, 700, 500, 900))
        assertNull(TextHighlightSpans.clipToPage(700, 600, 500, 900))
        assertNull(TextHighlightSpans.clipToPage(0, 100, 500, 500))
    }

    @Test
    fun `an exclusive end at the page edge keeps that last character`() {
        // Page [0,100): a span ending at 100 keeps its character 99, which is
        // what makes the last character of a page highlightable at all.
        assertEquals(0 to 100, TextHighlightSpans.clipToPage(0, 100, 0, 100))
        assertEquals(0 to 1, TextHighlightSpans.clipToPage(100, 101, 100, 200))
        // A span beginning exactly where the page ends belongs to the next page.
        assertNull(TextHighlightSpans.clipToPage(100, 150, 0, 100))
    }

    @Test
    fun `quotes are capped and clipped to the text`() {
        val text = "x".repeat(5_000)
        val quote = TextHighlightSpans.quoteForTxt(text, 0, 5_000)
        assertEquals(TextHighlightSpans.QUOTE_CAP_CHARS, quote.length)

        // End is exclusive, the same as every other offset in this model.
        assertEquals("mid", TextHighlightSpans.quoteForTxt("aa mid bb", 3, 6))
        assertEquals("", TextHighlightSpans.quoteForTxt("", 0, 10))
        assertEquals("", TextHighlightSpans.quoteForTxt("abc", 3, 2))
    }
}
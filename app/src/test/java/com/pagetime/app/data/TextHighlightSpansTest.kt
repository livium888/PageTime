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
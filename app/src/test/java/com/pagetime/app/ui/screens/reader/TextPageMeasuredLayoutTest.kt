package com.pagetime.app.ui.screens.reader

import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measured paginator must lay out pages by REAL rendered height: every page
 * fits the given screen height, no character is ever dropped, and page breaks
 * prefer readable boundaries. These tests use a deterministic fake measurer
 * (fixed-width characters, fixed line height) in place of Compose layout.
 */
class TextPageMeasuredLayoutTest {

    private companion object {
        const val CHARS_PER_LINE = 40
        const val LINE_HEIGHT_PX = 20
    }

    /** Fake of the real TextMeasurer: wraps at CHARS_PER_LINE, honors newlines. */
    private fun fakeMeasure(text: String): Int {
        val lines = text.split('\n').sumOf { line ->
            max(1, ceil(line.length / CHARS_PER_LINE.toDouble()).toInt())
        }
        return lines * LINE_HEIGHT_PX
    }

    @Test
    fun `every page fits the measured screen height`() = runTest {
        val content = ("A readable paragraph with enough words to exercise the " +
            "measurer. ").repeat(400)
        val maxHeight = 5 * LINE_HEIGHT_PX // 5 lines per page

        val pages = TextPageLayout.paginateMeasured(content, maxHeight, ::fakeMeasure)

        assertTrue(pages.size > 3)
        // Every page except (potentially) the last must fit the height budget.
        pages.dropLast(1).forEach { page ->
            assertTrue(
                "page ${page.index} measured ${fakeMeasure(page.text)} > $maxHeight",
                fakeMeasure(page.text) <= maxHeight
            )
        }
    }

    @Test
    fun `no character is ever lost across pages`() = runTest {
        val paragraphs = (0 until 60).joinToString("\n\n") { index ->
            "Paragraph $index tells a slightly different story with plenty of words."
        }
        val pages = TextPageLayout.paginateMeasured(paragraphs, 6 * LINE_HEIGHT_PX, ::fakeMeasure)

        assertEquals(
            paragraphs.filterNot(Char::isWhitespace),
            pages.joinToString(separator = "") { it.text }.filterNot(Char::isWhitespace)
        )
    }

    @Test
    fun `breaks prefer paragraph boundaries when the fit limit lands past them`() = runTest {
        // The second paragraph is one unbroken run: the raw fit limit lands
        // inside it, the nearest space boundary would orphan half of it, so the
        // paginator must walk back to the end of paragraph one.
        val para1 = "Short first paragraph."
        val para2 = "X".repeat(400)
        val content = "$para1\n\n$para2"
        val maxHeight = 5 * LINE_HEIGHT_PX // 5 lines at fake metrics

        val pages = TextPageLayout.paginateMeasured(content, maxHeight, ::fakeMeasure)

        assertEquals(para1, pages.first().text)
        assertTrue(pages.size > 1)
        pages.dropLast(1).forEach { page ->
            assertTrue(fakeMeasure(page.text) <= maxHeight)
        }
    }

    @Test
    fun `oversized unbroken paragraph still splits and never stalls`() = runTest {
        val giantWord = "w".repeat(50_000)
        val pages = TextPageLayout.paginateMeasured(giantWord, 5 * LINE_HEIGHT_PX, ::fakeMeasure)

        assertTrue(pages.size > 1)
        assertEquals(giantWord, pages.joinToString("") { it.text })
    }

    @Test
    fun `a page of ordinary words breaks at spaces not mid-word`() = runTest {
        val content = "word ".repeat(300).trim()
        val pages = TextPageLayout.paginateMeasured(content, 5 * LINE_HEIGHT_PX, ::fakeMeasure)

        assertTrue(pages.size > 1)
        pages.dropLast(1).forEach { page ->
            assertTrue(
                "page broke mid-word: ends with '${page.text.takeLast(8)}'",
                page.text.endsWith("word")
            )
        }
    }

    @Test
    fun `offset helpers stay stable for measured pages`() = runTest {
        val content = ("sentence with several words. ").repeat(500)
        val pages = TextPageLayout.paginateMeasured(content, 5 * LINE_HEIGHT_PX, ::fakeMeasure)

        assertEquals(0, TextPageLayout.pageForOffset(pages, 0))
        assertEquals(
            pages.lastIndex,
            TextPageLayout.pageForOffset(pages, content.length)
        )
        // Walk every page boundary through the offset helper — no page is skipped.
        pages.forEachIndexed { index, page ->
            assertEquals(index, TextPageLayout.pageForOffset(pages, page.startOffset))
        }
        assertEquals(0f, TextPageLayout.fractionForPage(0, pages.size))
        assertEquals(1f, TextPageLayout.fractionForPage(pages.lastIndex, pages.size))
    }

    @Test
    fun `measurement window caps how far ahead the search measures`() = runTest {
        // 40k chars of ordinary words. With a 2k-char window the search may only
        // ever measure snippets of ~2k chars, never the 40k-char remainder.
        val content = "word ".repeat(8_000).trim()
        var largestSnippet = 0
        val pages = TextPageLayout.paginateMeasured(
            content,
            5 * LINE_HEIGHT_PX,
            { snippet ->
                largestSnippet = max(largestSnippet, snippet.length)
                fakeMeasure(snippet)
            },
            maxWindowChars = 2_000
        )

        assertTrue(pages.size > 1)
        assertTrue(
            "search measured a $largestSnippet-char snippet; window cap leaked",
            largestSnippet <= 2_000 + 8 // small boundary slack from trimming
        )
    }

    @Test
    fun `windowed layout produces the same pages as uncapped for ordinary text`() = runTest {
        // A window well above page capacity must not change the layout at all.
        val content = ("Sentence with several words here. ").repeat(300)
        val capped = TextPageLayout.paginateMeasured(
            content, 5 * LINE_HEIGHT_PX, ::fakeMeasure, maxWindowChars = 4_000
        )
        val uncapped = TextPageLayout.paginateMeasured(content, 5 * LINE_HEIGHT_PX, ::fakeMeasure)

        assertEquals(uncapped, capped)
    }

    @Test
    fun `progress reaches 1 when layout finishes`() = runTest {
        val content = ("sentence with several words. ").repeat(500)
        var lastProgress = 0f
        TextPageLayout.paginateMeasured(
            content,
            5 * LINE_HEIGHT_PX,
            ::fakeMeasure
        ) { fraction -> lastProgress = fraction }

        assertEquals(1f, lastProgress)
    }
}

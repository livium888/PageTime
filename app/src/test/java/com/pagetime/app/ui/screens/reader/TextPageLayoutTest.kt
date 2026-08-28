package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPageLayoutTest {
    @Test
    fun `pagination prefers readable boundaries and preserves all words`() {
        val paragraph = "A readable paragraph with enough words to test boundaries. "
        val content = buildString {
            repeat(100) {
                append(paragraph)
                if (it % 4 == 3) append("\n\n")
            }
        }

        val pages = TextPageLayout.paginate(content)

        assertTrue(pages.size > 1)
        assertEquals(
            content.filterNot(Char::isWhitespace),
            pages.joinToString(separator = "") { it.text }.filterNot(Char::isWhitespace)
        )
        assertTrue(pages.dropLast(1).all { it.text.length >= 600 - 2 })
    }

    @Test
    fun `saved fractions map to stable first and last pages`() {
        val pages = TextPageLayout.paginate("word ".repeat(3_000))

        assertEquals(0, TextPageLayout.pageForFraction(pages, 0f))
        assertEquals(pages.lastIndex, TextPageLayout.pageForFraction(pages, 1f))
        assertEquals(0, TextPageLayout.pageForOffset(pages, 0))
        assertEquals(pages.lastIndex, TextPageLayout.pageForOffset(pages, Int.MAX_VALUE))
        assertEquals(0f, TextPageLayout.fractionForPage(0, pages.size))
        assertEquals(1f, TextPageLayout.fractionForPage(pages.lastIndex, pages.size))
    }
}

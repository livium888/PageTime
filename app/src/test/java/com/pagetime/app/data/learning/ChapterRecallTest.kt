package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRecallTest {

    private fun ask(
        left: Int = 3,
        entered: Int = 4,
        fraction: Float = 0.97f,
        words: Int? = 3_000,
        asked: Set<Int> = emptySet(),
        enabled: Boolean = true,
    ) = ChapterRecall.shouldAsk(left, entered, fraction, words, asked, enabled)

    @Test
    fun `asked after a chapter read through to its end`() {
        assertTrue(ask())
    }

    @Test
    fun `not when the next chapter was jumped to, or an earlier one`() {
        assertFalse(ask(left = 3, entered = 9))
        assertFalse(ask(left = 3, entered = 2))
    }

    @Test
    fun `not when the end of the chapter was skipped`() {
        assertFalse(ask(fraction = 0.4f))
        assertTrue(ask(fraction = ChapterRecall.READ_THROUGH))
    }

    @Test
    fun `not for a short chapter, or one whose length is not known yet`() {
        assertFalse(ask(words = 300))
        assertFalse(ask(words = null))
        assertTrue(ask(words = ChapterRecall.MIN_CHAPTER_WORDS))
    }

    @Test
    fun `once per chapter, and not at all when switched off`() {
        assertFalse(ask(asked = setOf(3)))
        assertTrue(ask(asked = setOf(2, 5)))
        assertFalse(ask(enabled = false))
    }

    @Test
    fun `the opening is the first words, whole, and says when there is more`() {
        val text = (1..200).joinToString(" ") { "w$it" }
        val opening = ChapterRecall.opening(text, words = 5)
        assertEquals("w1 w2 w3 w4 w5…", opening)
        assertEquals("one two", ChapterRecall.opening("  one two  ", words = 5))
        assertEquals("", ChapterRecall.opening("   "))
    }

    @Test
    fun `paragraph breaks in the opening are kept`() {
        assertEquals("First para.\n\nSecond…", ChapterRecall.opening("First para.\n\nSecond para here", words = 3))
    }
}

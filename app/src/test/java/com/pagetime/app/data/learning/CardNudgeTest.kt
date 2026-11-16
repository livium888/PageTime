package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardNudgeTest {

    @Test
    fun `nothing is owed until enough has been read`() {
        assertFalse(CardNudge.due(words = 1_499, laterAt = 0, every = 1_500))
        assertTrue(CardNudge.due(words = 1_500, laterAt = 0, every = 1_500))
    }

    @Test
    fun `later keeps counting and asks again after the same number of words`() {
        // Declined at 1,620: quiet until 3,120, then owed with the new total.
        assertFalse(CardNudge.due(words = 1_700, laterAt = 1_620, every = 1_500))
        assertFalse(CardNudge.due(words = 3_119, laterAt = 1_620, every = 1_500))
        assertTrue(CardNudge.due(words = 3_120, laterAt = 1_620, every = 1_500))
        assertEquals(3_120, CardNudge.nextAskAt(words = 2_000, laterAt = 1_620, every = 1_500))
    }

    @Test
    fun `making cards starts the count over`() {
        // The stretch is short again, so the old "later" no longer applies.
        assertEquals(0, CardNudge.laterStillApplies(words = 200, laterAt = 3_120))
        assertFalse(CardNudge.due(words = 200, laterAt = 3_120, every = 1_500))
        assertTrue(CardNudge.due(words = 1_500, laterAt = 3_120, every = 1_500))
    }

    @Test
    fun `the offer follows the reader's density and says when it has piled up`() {
        val normal = CardNudge.offer(1_620, CardDensity.NORMAL)
        assertEquals(5, normal.cards)
        assertEquals("1,620 words read · about 5 cards", normal.headline)
        assertNull(normal.detail)
        assertEquals(3, CardNudge.offer(1_620, CardDensity.FEW).cards)

        val long = CardNudge.offer(5_200, CardDensity.NORMAL)
        assertTrue(long.long)
        assertTrue(long.detail != null)
    }

    @Test
    fun `an owed suggestion waits for a chapter or section to begin, however long that takes`() {
        assertTrue(CardNudge.showNow(atBreak = true))
        assertFalse(CardNudge.showNow(atBreak = false))
    }

    @Test
    fun `only the start of the text or a page with a heading begins a section`() {
        assertTrue(CardNudge.pageStartsAtBreak(0, 10, emptyList()))
        assertTrue(CardNudge.pageStartsAtBreak(400, 900, listOf(650)))
        // A new paragraph is not a section, and a heading on another page does not count.
        assertFalse(CardNudge.pageStartsAtBreak(400, 900, emptyList()))
        assertFalse(CardNudge.pageStartsAtBreak(400, 900, listOf(2, 900)))
    }

    @Test
    fun `a new chapter is a break in an EPUB`() {
        assertTrue(CardNudge.chapterBreak(previousChapter = 3, chapter = 4, fraction = 0.4f))
        assertTrue(CardNudge.chapterBreak(previousChapter = 4, chapter = 4, fraction = 0f))
        assertFalse(CardNudge.chapterBreak(previousChapter = 4, chapter = 4, fraction = 0.4f))
        assertFalse(CardNudge.chapterBreak(previousChapter = null, chapter = 4, fraction = 0.4f))
    }

    @Test
    fun `an unknown interval falls back to the default`() {
        assertEquals(CardNudge.DEFAULT_EVERY, CardNudge.everyOrDefault(null))
        assertEquals(CardNudge.DEFAULT_EVERY, CardNudge.everyOrDefault(7))
        assertEquals(2_500, CardNudge.everyOrDefault(2_500))
    }
}

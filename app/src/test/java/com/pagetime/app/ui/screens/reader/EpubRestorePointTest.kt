package com.pagetime.app.ui.screens.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubRestorePointTest {

    /** The engine's own rule for which page a position opens on. */
    private fun pageOpened(progression: Double, pages: Int): Int = (progression * pages).toInt()

    @Test
    fun `the exact start of a page can reopen on the page before`() {
        // 0.29 is where page 29 of 100 begins, and it rounds down to page 28.
        assertEquals(28, pageOpened(0.29, 100))
    }

    @Test
    fun `half a page in reopens on the same page, for every page of a chapter`() {
        for (pages in listOf(3, 7, 100, 333, 1000)) {
            val step = 1.0 / pages
            for (page in 0 until pages) {
                val saved = EpubRestorePoint.inside(page.toDouble() / pages, step)
                assertEquals("page $page of $pages", page, pageOpened(saved, pages))
            }
        }
    }

    @Test
    fun `before the page size is known the nudge is small enough to stay in the page`() {
        // A chapter would need thousands of pages for this to cross into the next.
        val saved = EpubRestorePoint.inside(0.40, pageStep = null)
        assertEquals(40, pageOpened(saved, 100))
        assertEquals(400, pageOpened(EpubRestorePoint.inside(0.40, null), 1000))
    }

    @Test
    fun `the page size is the smallest gap seen between two positions`() {
        assertNull(EpubRestorePoint.pageStep(null, 0.2, 0.2))
        assertEquals(0.01, EpubRestorePoint.pageStep(null, 0.20, 0.21)!!, 1e-9)
        // A jump through the contents does not make pages look bigger.
        assertEquals(0.01, EpubRestorePoint.pageStep(0.01, 0.21, 0.80)!!, 1e-9)
        // Going back a page counts the same as going forward.
        assertEquals(0.01, EpubRestorePoint.pageStep(null, 0.21, 0.20)!!, 1e-9)
    }

    @Test
    fun `the end of a chapter stays inside the chapter`() {
        assertTrue(EpubRestorePoint.inside(1.0, 0.1) < 1.0)
    }

    @Test
    fun `only the place in the chapter is changed in a saved position`() {
        val saved = """{"href":"ch14.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"position":212}}"""
        val moved = JSONObject(EpubRestorePoint.withProgression(saved, 0.405))
        assertEquals("ch14.xhtml", moved.getString("href"))
        assertEquals(0.405, moved.getJSONObject("locations").getDouble("progression"), 1e-9)
        assertEquals(212, moved.getJSONObject("locations").getInt("position"))
        // Something that is not a position is handed back as it came.
        assertEquals("not json", EpubRestorePoint.withProgression("not json", 0.5))
    }
}

package com.pagetime.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingVolumeTest {

    @Test
    fun `labels read as a person would say them`() {
        assertEquals("about 84,000", ReadingVolume.Summary(2, 84_321).wordsLabel)
        assertEquals("about 4,300", ReadingVolume.Summary(0, 4_321).wordsLabel)
        assertEquals("0", ReadingVolume.Summary(0, 0).wordsLabel)
        assertEquals("1 book", ReadingVolume.Summary(1, 0).booksLabel)
        assertEquals("3 books", ReadingVolume.Summary(3, 0).booksLabel)
        assertEquals("0 books", ReadingVolume.Summary(0, 0).booksLabel)
    }

    @Test
    fun `nothing read is empty`() {
        assertTrue(ReadingVolume.Summary(0, 0).isEmpty)
    }
}

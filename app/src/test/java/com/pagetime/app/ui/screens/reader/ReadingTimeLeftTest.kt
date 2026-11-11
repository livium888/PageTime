package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingTimeLeftTest {

    @Test
    fun `hours and minutes left, from the words still to read`() {
        // Half of 46,560 words at 240 a minute is 97 minutes.
        assertEquals("1 hr 37 mins left in book", ReadingTimeLeft.label(46_560, 0.5f))
        assertEquals("12 mins left in book", ReadingTimeLeft.label(2_880, 0f))
        assertEquals("1 min left in book", ReadingTimeLeft.label(240, 0f))
        assertEquals("2 hrs left in book", ReadingTimeLeft.label(28_800, 0f))
        assertEquals("1 hr 1 min left in book", ReadingTimeLeft.label(14_640, 0f))
    }

    @Test
    fun `nothing to say without a word count or at the end`() {
        assertNull(ReadingTimeLeft.label(null, 0.5f))
        assertNull(ReadingTimeLeft.label(0, 0.5f))
        assertNull(ReadingTimeLeft.label(50_000, 1f))
    }
}

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

class ReadingTodayTest {

    @Test
    fun `read, target and what is left`() {
        assertEquals("12 of 20 min · 8 left", ReadingToday.label(12 * 60 + 40, 20 * 60, lockOn = true))
        assertEquals("0 of 20 min · 20 left", ReadingToday.label(30, 20 * 60, lockOn = true))
    }

    @Test
    fun `done once the target is reached, and it keeps counting`() {
        assertEquals("23 of 20 min · done", ReadingToday.label(23 * 60, 20 * 60, lockOn = true))
    }

    @Test
    fun `with the lock off there is no target to count down to`() {
        assertEquals("7 min read today", ReadingToday.label(7 * 60, 20 * 60, lockOn = false))
    }

    @Test
    fun `a stopped clock says why`() {
        assertEquals(
            "3 of 20 min · 17 left · paused: turn a page to go on",
            ReadingToday.label(180, 1200, lockOn = true, notCounting = ReadingToday.notCounting(idle = true, tooFast = false)),
        )
        assertEquals("paused: pages turning too fast", ReadingToday.notCounting(idle = true, tooFast = true))
        assertNull(ReadingToday.notCounting(idle = false, tooFast = false))
    }
}

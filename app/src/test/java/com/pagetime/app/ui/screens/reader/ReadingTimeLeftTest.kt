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
    fun `past the target it stops counting down and says only what was read`() {
        assertEquals("23 min read today", ReadingToday.label(23 * 60, 20 * 60, lockOn = true))
    }

    @Test
    fun `past the target a nearby chapter end is where it points`() {
        assertEquals(
            "23 min read · 4 min to the end of the chapter",
            ReadingToday.label(23 * 60, 20 * 60, lockOn = true, chapterMinutesLeft = 4),
        )
        // Too far to be an invitation, and never before the target is met.
        assertEquals("23 min read today", ReadingToday.label(23 * 60, 20 * 60, lockOn = true, chapterMinutesLeft = 40))
        assertEquals(
            "12 of 20 min · 8 left",
            ReadingToday.label(12 * 60, 20 * 60, lockOn = true, chapterMinutesLeft = 4),
        )
    }

    @Test
    fun `the countdown can be switched off`() {
        assertEquals("12 min read today", ReadingToday.label(12 * 60, 20 * 60, lockOn = true, showCountdown = false))
    }

    @Test
    fun `with the lock off there is no target to count down to`() {
        assertEquals("7 min read today", ReadingToday.label(7 * 60, 20 * 60, lockOn = false))
    }

    @Test
    fun `minutes to read some words, rounded up`() {
        assertEquals(1, ReadingToday.minutesFor(10))
        assertEquals(4, ReadingToday.minutesFor(900))
        assertNull(ReadingToday.minutesFor(0))
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

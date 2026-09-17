package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStreakTest {

    private val today = 20_000L

    @Test
    fun `no history is no streak`() {
        assertEquals(0, ReadingStreak.currentStreak(emptySet(), today))
        assertFalse(ReadingStreak.didToday(emptySet(), today))
    }

    @Test
    fun `reading only today is a streak of one`() {
        assertEquals(1, ReadingStreak.currentStreak(setOf(today), today))
        assertTrue(ReadingStreak.didToday(setOf(today), today))
    }

    @Test
    fun `consecutive days before today extend the streak`() {
        val activeDays = setOf(today, today - 1, today - 2, today - 3)
        assertEquals(4, ReadingStreak.currentStreak(activeDays, today))
    }

    @Test
    fun `a gap breaks the streak at the gap, not before it`() {
        // today and yesterday read, then a missed day, then more reading further back.
        val activeDays = setOf(today, today - 1, today - 3, today - 4)
        assertEquals(2, ReadingStreak.currentStreak(activeDays, today))
    }

    @Test
    fun `not reading today but having read yesterday is a streak of zero, not carried over`() {
        // The streak is "consecutive days ending today" — a miss today, even
        // before the day is over, means the count is zero until today has an
        // event of its own.
        val activeDays = setOf(today - 1, today - 2)
        assertEquals(0, ReadingStreak.currentStreak(activeDays, today))
    }

    @Test
    fun `brand-new reader with zero history never gets the nudge`() {
        assertFalse(ReadingStreak.needsNeverMissTwiceNudge(emptySet(), today))
    }

    @Test
    fun `reading today never triggers the nudge, regardless of yesterday`() {
        assertFalse(ReadingStreak.needsNeverMissTwiceNudge(setOf(today, today - 5), today))
    }

    @Test
    fun `reading yesterday never triggers the nudge`() {
        assertFalse(ReadingStreak.needsNeverMissTwiceNudge(setOf(today - 1, today - 5), today))
    }

    @Test
    fun `a miss after real history triggers the nudge`() {
        val activeDays = setOf(today - 2, today - 3, today - 4)
        assertTrue(ReadingStreak.needsNeverMissTwiceNudge(activeDays, today))
    }

    @Test
    fun `two missed days in a row no longer counts as never-miss-twice`() {
        // The nudge is for catching the FIRST miss before it becomes a
        // second one. Once two days have already gone by, the chain is
        // already broken and this isn't the message for that anymore, but it
        // still recognizes the situation rather than crashing or flip-flopping.
        val activeDays = setOf(today - 3, today - 4)
        assertTrue(ReadingStreak.needsNeverMissTwiceNudge(activeDays, today))
    }
}

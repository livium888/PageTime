package com.pagetime.app.domain

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LockDayTest {

    private val london = ZoneId.of("Europe/London")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(london).toInstant().toEpochMilli()

    private fun day(text: String): Long = LockDay.of(at(text), london)

    @Test
    fun `the day changes at four in the morning, not at midnight`() {
        val evening = day("2026-03-10T22:00")
        assertEquals(evening, day("2026-03-11T00:30"))
        assertEquals(evening, day("2026-03-11T03:59"))
        assertNotEquals(evening, day("2026-03-11T04:00"))
        assertEquals(evening + 1, day("2026-03-11T04:00"))
    }

    /** Clocks go forward 01:00 → 02:00 on 2026-03-29 in the UK. */
    @Test
    fun `the reset stays at four on the wall clock across the spring change`() {
        assertEquals(day("2026-03-28T22:00"), day("2026-03-29T03:59"))
        assertEquals(day("2026-03-28T22:00") + 1, day("2026-03-29T04:00"))
    }

    /** Clocks go back 02:00 → 01:00 on 2026-10-25 in the UK. */
    @Test
    fun `the reset stays at four on the wall clock across the autumn change`() {
        assertEquals(day("2026-10-24T22:00"), day("2026-10-25T03:59"))
        assertEquals(day("2026-10-24T22:00") + 1, day("2026-10-25T04:00"))
    }

    @Test
    fun `the next reset is today at four if it is earlier than four`() {
        assertEquals(at("2026-03-11T04:00"), LockDay.nextResetAt(at("2026-03-11T02:00"), london))
    }

    @Test
    fun `the next reset is tomorrow at four once four has passed`() {
        assertEquals(at("2026-03-12T04:00"), LockDay.nextResetAt(at("2026-03-11T04:00"), london))
        assertEquals(at("2026-03-12T04:00"), LockDay.nextResetAt(at("2026-03-11T23:59"), london))
    }

    @Test
    fun `reading stored for another day counts for nothing`() {
        assertEquals(900L, LockDay.readToday(storedDay = 100, storedSeconds = 900, today = 100))
        assertEquals(0L, LockDay.readToday(storedDay = 99, storedSeconds = 900, today = 100))
        assertEquals(0L, LockDay.readToday(storedDay = 100, storedSeconds = -5, today = 100))
    }
}

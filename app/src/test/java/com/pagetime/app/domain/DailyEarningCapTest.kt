package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyEarningCapTest {

    @Test
    fun `a stored tally from a previous day doesn't count against today`() {
        assertEquals(0L, DailyEarningCap.earnedSoFar(storedEpochDay = 5L, today = 6L, storedSeconds = 200L))
    }

    @Test
    fun `a stored tally from today counts in full`() {
        assertEquals(200L, DailyEarningCap.earnedSoFar(storedEpochDay = 6L, today = 6L, storedSeconds = 200L))
    }

    @Test
    fun `nothing earned yet today reads as zero`() {
        assertEquals(0L, DailyEarningCap.earnedSoFar(storedEpochDay = 0L, today = 6L, storedSeconds = 0L))
    }

    @Test
    fun `pays the full request when well under the cap`() {
        assertEquals(30L, DailyEarningCap.payable(requestedSeconds = 30L, earnedSoFarToday = 0L, capSeconds = 300L))
    }

    @Test
    fun `pays only what's left of the cap when the request would overshoot it`() {
        assertEquals(10L, DailyEarningCap.payable(requestedSeconds = 30L, earnedSoFarToday = 290L, capSeconds = 300L))
    }

    @Test
    fun `pays nothing once the cap is already reached`() {
        assertEquals(0L, DailyEarningCap.payable(requestedSeconds = 30L, earnedSoFarToday = 300L, capSeconds = 300L))
    }

    @Test
    fun `pays nothing if earnings somehow already exceed the cap`() {
        assertEquals(0L, DailyEarningCap.payable(requestedSeconds = 30L, earnedSoFarToday = 500L, capSeconds = 300L))
    }

    @Test
    fun `a cap of zero pays nothing at all, by design`() {
        assertEquals(0L, DailyEarningCap.payable(requestedSeconds = 30L, earnedSoFarToday = 0L, capSeconds = 0L))
    }
}

package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessGateTest {

    private fun gate(
        enabled: Boolean = true,
        reading: Long = 0,
        planning: Long = 0,
        threshold: Long = GateState.DEFAULT_THRESHOLD_SECONDS,
        cap: Long = GateState.DEFAULT_PLANNING_CAP_SECONDS,
    ) = GateState(enabled, reading, planning, threshold, cap)

    @Test
    fun `below the line the apps stay shut`() {
        val g = gate(reading = 2 * 60 * 60 - 1)
        assertFalse(g.open)
        assertEquals(1L, g.remainingSeconds)
    }

    @Test
    fun `the line itself opens them`() {
        val g = gate(reading = 2 * 60 * 60)
        assertTrue(g.open)
        assertEquals(0L, g.remainingSeconds)
    }

    @Test
    fun `a disabled gate opens regardless of how little was read`() {
        assertTrue(gate(enabled = false, reading = 0).open)
    }

    /**
     * The rule the whole feature turns on. Reading a minute used to buy a
     * minute; now a minute buys nothing at all until the line is crossed.
     */
    @Test
    fun `a minute of reading does not buy a minute of apps`() {
        val before = gate(reading = 0)
        val after = gate(reading = 60)
        assertFalse(before.open)
        assertFalse(after.open)
    }

    @Test
    fun `planning time counts toward the line`() {
        val g = gate(reading = 2 * 60 * 60 - 300, planning = 300)
        assertTrue(g.open)
    }

    @Test
    fun `planning past the cap stops counting`() {
        val g = gate(reading = 0, planning = 10 * 60 * 60, cap = 20 * 60)
        assertEquals(20L * 60, g.countedPlanningSeconds)
        assertEquals(20L * 60, g.accruedSeconds)
        assertFalse(g.open)
    }

    /**
     * The cap's reason for existing: an afternoon of talking about reading is
     * not an afternoon of reading, and must not open the phone on its own.
     */
    @Test
    fun `talking alone can never open the gate`() {
        val g = gate(reading = 0, planning = Long.MAX_VALUE / 2)
        assertFalse(g.open)
        assertEquals(GateState.DEFAULT_THRESHOLD_SECONDS - GateState.DEFAULT_PLANNING_CAP_SECONDS,
            g.remainingSeconds)
    }

    /**
     * Even if the stored cap is nonsense. A cap at or above the threshold would
     * quietly convert the gate into "chat for two hours", so it is clamped
     * rather than trusted.
     */
    @Test
    fun `a cap larger than the whole gate is clamped to it`() {
        val g = gate(reading = 0, planning = 5 * 60 * 60, threshold = 7200, cap = 99 * 60 * 60)
        assertEquals(7200L, g.effectivePlanningCapSeconds)
        // Clamped to the threshold rather than beyond it, so the gate is still
        // reachable by planning at that (rejected) setting but never exceeded.
        assertEquals(7200L, g.accruedSeconds)
    }

    @Test
    fun `the planning bucket empties as it is used`() {
        assertEquals(20L * 60, gate(planning = 0).planningRemainingSeconds)
        assertEquals(5L * 60, gate(planning = 15 * 60).planningRemainingSeconds)
        assertEquals(0L, gate(planning = 60 * 60).planningRemainingSeconds)
    }

    @Test
    fun `progress is a fraction of the line and never leaves zero to one`() {
        assertEquals(0f, gate(reading = 0).progress, 0.0001f)
        assertEquals(0.5f, gate(reading = 60 * 60).progress, 0.0001f)
        assertEquals(1f, gate(reading = 99 * 60 * 60).progress, 0.0001f)
    }

    @Test
    fun `a threshold of zero is a gate that is simply open`() {
        val g = gate(reading = 0, threshold = 0)
        assertTrue(g.open)
        assertEquals(1f, g.progress, 0.0001f)
        assertEquals(0L, g.remainingSeconds)
    }

    /**
     * Reading seconds come from a ledger sum, which is a SQL SUM over rows
     * anyone could in principle corrupt. Nothing here should answer with a
     * negative distance.
     */
    /**
     * The default configuration must not be one where talking is half the day.
     */
    @Test
    fun `the shipped defaults are a real reading gate`() {
        assertEquals(2L * 60 * 60, GateState.DEFAULT_THRESHOLD_SECONDS)
        assertTrue(
            GateState.DEFAULT_PLANNING_CAP_SECONDS <=
                GateState.maxPlanningCapFor(GateState.DEFAULT_THRESHOLD_SECONDS)
        )
        // At the defaults, at least an hour and forty minutes must be reading.
        val talkedOut = gate(reading = 0, planning = 99 * 60 * 60)
        assertEquals(100L * 60, talkedOut.remainingSeconds)
    }

    @Test
    fun `the planning cap is never more than half the gate`() {
        assertEquals(0L, GateState.maxPlanningCapFor(0))
        assertEquals(0L, GateState.maxPlanningCapFor(-100))
        assertEquals(3600L, GateState.maxPlanningCapFor(7200))
        assertEquals(450L, GateState.maxPlanningCapFor(900))
    }

    @Test
    fun `nonsense inputs do not produce negative numbers`() {
        val g = gate(reading = -500, planning = -500)
        assertEquals(0L, g.accruedSeconds)
        assertEquals(GateState.DEFAULT_THRESHOLD_SECONDS, g.remainingSeconds)
        assertFalse(g.open)
    }
}

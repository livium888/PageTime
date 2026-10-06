package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessGateTest {

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val TARGET = GateState.DEFAULT_DAILY_TARGET_SECONDS // 1200
        const val MIN = GateState.MIN_DAILY_TARGET_SECONDS
        const val MAX = GateState.MAX_DAILY_TARGET_SECONDS
    }

    private fun gate(
        switchedOn: Boolean = true,
        read: Long = 0,
        disableAt: Long = 0,
        now: Long = T0,
        target: Long = TARGET,
    ) = GateState(switchedOn, read, disableAt, now, target)

    // --- The lock ---

    @Test
    fun `before today's reading the phone is locked`() {
        assertFalse(gate(read = 0).open)
        assertFalse(gate(read = TARGET - 1).open)
        assertEquals(1L, gate(read = TARGET - 1).secondsToUnlock)
    }

    @Test
    fun `reading the target opens the phone`() {
        val g = gate(read = TARGET)
        assertTrue(g.targetMet)
        assertTrue(g.open)
        assertEquals(0L, g.secondsToUnlock)
    }

    @Test
    fun `reading past the target keeps it open and the distance at zero`() {
        val g = gate(read = TARGET * 3)
        assertTrue(g.open)
        assertEquals(0L, g.secondsToUnlock)
        assertEquals(1f, g.progress)
    }

    @Test
    fun `progress is the share of today's target read`() {
        assertEquals(0f, gate(read = 0).progress)
        assertEquals(0.5f, gate(read = TARGET / 2).progress)
    }

    @Test
    fun `the shipped default is twenty minutes`() {
        assertEquals(20L * 60, GateState.DEFAULT_DAILY_TARGET_SECONDS)
    }

    // --- Sites ---

    @Test
    fun `today's reading opens blocked sites too`() {
        assertTrue(gate(read = TARGET).coversSites)
        assertFalse(gate(read = TARGET - 1).coversSites)
    }

    @Test
    fun `with the lock off, sites stay exactly as blocked as the rule says`() {
        val off = gate(switchedOn = false, read = TARGET * 2)
        assertTrue(off.open)
        assertFalse(off.coversSites)
    }

    // --- Switching off takes a day ---

    @Test
    fun `switching off does not take effect for a day`() {
        val g = gate(read = 0, disableAt = T0 + GateState.COOLING_OFF_MILLIS)
        assertTrue(g.enabled)
        assertTrue(g.windingDown)
        assertFalse(g.open)
        assertEquals(24L * 60 * 60, g.secondsUntilDisabled)
    }

    @Test
    fun `once the day has passed the lock really is off`() {
        val disableAt = T0 + GateState.COOLING_OFF_MILLIS
        val g = gate(read = 0, disableAt = disableAt, now = disableAt)
        assertFalse(g.enabled)
        assertTrue(g.open)
        assertEquals(0L, g.secondsUntilDisabled)
    }

    @Test
    fun `a lock that was never switched on is simply off`() {
        val g = gate(switchedOn = false)
        assertFalse(g.enabled)
        assertTrue(g.open)
        assertFalse(g.windingDown)
    }

    // --- Loosening is earned ---

    @Test
    fun `the rules cannot be loosened before today's reading`() {
        val g = gate(read = TARGET - 1)
        assertFalse(g.canLoosenTheRules)
        assertFalse(g.canRemoveBlockedApps)
        assertFalse(g.canAddAllowedSite)
        assertFalse(g.canAddAllowedApp)
        assertFalse(g.canSwitchToBlocklist)
    }

    @Test
    fun `after today's reading the rules can be loosened`() {
        val g = gate(read = TARGET)
        assertTrue(g.canLoosenTheRules)
        assertTrue(g.canAddAllowedApp)
        assertTrue(g.canSwitchToBlocklist)
    }

    @Test
    fun `winding down does not unfreeze the rules early`() {
        val g = gate(read = 0, disableAt = T0 + GateState.COOLING_OFF_MILLIS)
        assertFalse(g.canLoosenTheRules)
    }

    @Test
    fun `with the lock off nothing is fenced`() {
        assertTrue(gate(switchedOn = false).canLoosenTheRules)
    }

    // --- Nonsense in storage ---

    @Test
    fun `a stored target of nothing still demands the minimum`() {
        val g = gate(read = 0, target = 0)
        assertEquals(MIN, g.target)
        assertFalse(g.open)
        assertTrue(gate(read = MIN, target = 0).open)
    }

    @Test
    fun `a negative reading counter is not reading`() {
        val g = gate(read = -500)
        assertEquals(0L, g.readToday)
        assertEquals(TARGET, g.secondsToUnlock)
        assertEquals(0f, g.progress)
    }

    @Test
    fun `before anything is known the lock is off`() {
        assertFalse(GateState.Unknown.enabled)
        assertTrue(GateState.Unknown.open)
    }

    // --- The target control's fence ---

    @Test
    fun `before today's reading the target may only go up`() {
        val bounds = GateState.targetBounds(TARGET, canLoosen = false)
        assertEquals(TARGET, bounds.first)
        assertEquals(MAX, bounds.last)
    }

    @Test
    fun `after today's reading the target moves anywhere`() {
        assertEquals(MIN..MAX, GateState.targetBounds(TARGET, canLoosen = true))
    }

    @Test
    fun `lower is loosening, higher is not`() {
        assertTrue(GateState.loosensTarget(TARGET, TARGET - 60))
        assertFalse(GateState.loosensTarget(TARGET, TARGET + 60))
        assertFalse(GateState.loosensTarget(TARGET, TARGET))
    }

    /** An inverted range would give the slider negative travel. */
    @Test
    fun `a stored target outside the range still gives a sane fence`() {
        val low = GateState.targetBounds(0, canLoosen = false)
        assertEquals(MIN, low.first)
        assertTrue(low.first <= low.last)
        val high = GateState.targetBounds(MAX * 10, canLoosen = false)
        assertEquals(MAX..MAX, high)
        assertFalse(GateState.hasTravel(high))
    }

    @Test
    fun `a target in the middle always has travel`() {
        assertTrue(GateState.hasTravel(GateState.targetBounds(TARGET, canLoosen = false)))
    }
}

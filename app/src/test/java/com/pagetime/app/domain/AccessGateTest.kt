package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessGateTest {

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val COST = GateState.DEFAULT_SESSION_COST_SECONDS      // 7200
        const val LENGTH = GateState.DEFAULT_SESSION_LENGTH_SECONDS  // 1800
    }

    private fun gate(
        switchedOn: Boolean = true,
        credit: Long = 0,
        sessionRemaining: Long = 0,
        disableAt: Long = 0,
        now: Long = T0,
        cost: Long = COST,
        length: Long = LENGTH,
    ) = GateState(switchedOn, credit, sessionRemaining, disableAt, now, cost, length)

    // --- The exchange ---

    @Test
    fun `a minute of reading buys nothing`() {
        assertFalse(gate(credit = 60).canStartSession)
        assertFalse(gate(credit = 60).open)
        assertFalse(gate(credit = COST - 1).canStartSession)
    }

    @Test
    fun `the full price buys a session`() {
        assertTrue(gate(credit = COST).canStartSession)
        assertEquals(0L, gate(credit = COST).secondsToNextSession)
    }

    /**
     * The heart of it: the door being affordable is not the door being open.
     * Credit has to be spent deliberately, which is what makes a session a
     * thing you decide to take rather than a state you drift into.
     */
    @Test
    fun `affording a session is not the same as being in one`() {
        val afford = gate(credit = COST * 2)
        assertTrue(afford.canStartSession)
        assertFalse(afford.open)
    }

    // --- The session ---

    @Test
    fun `unspent app time opens the apps`() {
        val g = gate(credit = 0, sessionRemaining = 20 * 60)
        assertTrue(g.sessionActive)
        assertTrue(g.open)
        assertEquals(20L * 60, g.sessionRemainingSeconds)
    }

    @Test
    fun `spent-out app time closes them again`() {
        val g = gate(sessionRemaining = 0)
        assertFalse(g.sessionActive)
        assertFalse(g.open)
    }

    /**
     * A meter, not a wall clock. Nothing in this state depends on the time,
     * so app time cannot evaporate while the phone is face-down — the reader
     * paid two hours for it and it is still there tomorrow.
     */
    @Test
    fun `app time does not run down on its own`() {
        val g = gate(sessionRemaining = 12 * 60)
        val aWeekLater = g.copy(nowMillis = T0 + 7L * 24 * 60 * 60 * 1000)
        assertEquals(g.sessionRemainingSeconds, aWeekLater.sessionRemainingSeconds)
        assertTrue(aWeekLater.open)
    }

    /**
     * Buying more while some is left is allowed — it is the reader's two
     * hours — but it stops at the ceiling, or a month of reading could
     * stockpile an afternoon of scrolling.
     */
    @Test
    fun `app time can be topped up but not hoarded`() {
        assertTrue(gate(credit = COST, sessionRemaining = LENGTH).canStartSession)
        assertFalse(gate(credit = COST * 2, sessionRemaining = LENGTH * 2).canStartSession)
        assertEquals(LENGTH * 2, GateState.maxSessionSecondsFor(LENGTH))
    }

    // --- Banking ---

    @Test
    fun `credit banks whole sessions`() {
        assertEquals(0, gate(credit = COST - 1).sessionsBanked)
        assertEquals(1, gate(credit = COST).sessionsBanked)
        assertEquals(2, gate(credit = COST * 2).sessionsBanked)
    }

    @Test
    fun `banking is capped at two sessions`() {
        assertEquals(COST * 2, GateState.maxCreditFor(COST))
        assertEquals(0L, GateState.maxCreditFor(0))
        assertEquals(0L, GateState.maxCreditFor(-5))
    }

    @Test
    fun `progress reads as fullness toward the next session`() {
        assertEquals(0f, gate(credit = 0).creditProgress, 0.0001f)
        assertEquals(0.5f, gate(credit = COST / 2).creditProgress, 0.0001f)
        // A whole session banked is full, not back to empty.
        assertEquals(1f, gate(credit = COST).creditProgress, 0.0001f)
        // Part-way to a second.
        assertEquals(0.25f, gate(credit = COST + COST / 4).creditProgress, 0.0001f)
    }

    // --- The off switch ---

    @Test
    fun `switching off does not take effect for a day`() {
        val g = gate(switchedOn = true, disableAt = T0 + GateState.COOLING_OFF_MILLIS)
        assertTrue(g.enabled)
        assertTrue(g.windingDown)
        assertFalse(g.open)
        assertEquals(24L * 60 * 60, g.secondsUntilDisabled)
    }

    @Test
    fun `once the day has passed the gate really is off`() {
        val g = gate(switchedOn = true, disableAt = T0 - 1, now = T0)
        assertFalse(g.enabled)
        assertFalse(g.windingDown)
        assertTrue(g.open)
    }

    /**
     * Nothing runs to expire the cooling-off. The rule is a function of the
     * clock, so the next question anyone asks gets the right answer even if
     * the process died for the whole day in between.
     */
    @Test
    fun `the cooling-off expires without anything having to run`() {
        val flipped = gate(switchedOn = true, disableAt = T0 + GateState.COOLING_OFF_MILLIS)
        assertTrue(flipped.enabled)
        val muchLater = flipped.copy(nowMillis = T0 + 40L * 24 * 60 * 60 * 1000)
        assertFalse(muchLater.enabled)
    }

    @Test
    fun `a gate that was never switched on is simply off`() {
        val g = gate(switchedOn = false, credit = 0)
        assertFalse(g.enabled)
        assertTrue(g.open)
        assertFalse(g.canStartSession)
        assertTrue(g.canRemoveBlockedApps)
    }

    // --- The list freeze, which is what makes the rest mean anything ---

    /**
     * Without this the gate is decorative: two hours of reading, or Settings →
     * uncheck. The escape and the front door now cost the same.
     */
    @Test
    fun `apps cannot be unblocked while the reading is being done`() {
        assertFalse(gate(credit = 0).canRemoveBlockedApps)
        assertFalse(gate(credit = COST).canRemoveBlockedApps)
    }

    @Test
    fun `apps can be unblocked while app time is in hand`() {
        assertTrue(gate(sessionRemaining = 60).canRemoveBlockedApps)
    }

    @Test
    fun `winding down does not unfreeze the list early`() {
        val g = gate(disableAt = T0 + GateState.COOLING_OFF_MILLIS)
        assertFalse(g.canRemoveBlockedApps)
    }

    // --- Degenerate configurations ---

    @Test
    fun `a zero cost does not divide by zero`() {
        val g = gate(credit = 0, cost = 0)
        assertEquals(1f, g.creditProgress, 0.0001f)
        assertEquals(0, g.sessionsBanked)
        assertTrue(g.canStartSession)
    }

    @Test
    fun `nonsense credit does not produce negative distances`() {
        val g = gate(credit = -500)
        assertEquals(COST, g.secondsToNextSession)
        assertFalse(g.canStartSession)
        assertEquals(0, g.sessionsBanked)
    }

    @Test
    fun `a negative counter is not app time`() {
        assertFalse(gate(sessionRemaining = -30).sessionActive)
        assertEquals(0L, gate(sessionRemaining = -30).sessionRemainingSeconds)
    }

    @Test
    fun `the shipped defaults are four hours of reading to one of apps`() {
        assertEquals(2L * 60 * 60, GateState.DEFAULT_SESSION_COST_SECONDS)
        assertEquals(30L * 60, GateState.DEFAULT_SESSION_LENGTH_SECONDS)
        assertEquals(4L, GateState.DEFAULT_SESSION_COST_SECONDS / GateState.DEFAULT_SESSION_LENGTH_SECONDS)
    }
}

package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyUnlockTest {

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
    }

    // --- The scope, which is the whole point ---

    /**
     * The rule that makes this a hatch rather than a bypass. An unlock granted
     * for a banking app must not quietly cover the next thing opened.
     */
    @Test
    fun `an unlock covers one app and no other`() {
        val until = T0 + 5 * 60 * 1000
        assertTrue(EmergencyUnlock.covers("com.bank", until, "com.bank", T0))
        assertFalse(EmergencyUnlock.covers("com.bank", until, "com.instagram", T0))
    }

    @Test
    fun `an unlock with no package covers nothing`() {
        val until = T0 + 5 * 60 * 1000
        assertFalse(EmergencyUnlock.covers(null, until, "com.bank", T0))
        assertFalse(EmergencyUnlock.covers("", until, "com.bank", T0))
        assertFalse(EmergencyUnlock.covers("com.bank", until, null, T0))
    }

    @Test
    fun `an expired unlock covers nothing, even its own app`() {
        val until = T0
        assertFalse(EmergencyUnlock.covers("com.bank", until, "com.bank", T0))
        assertFalse(EmergencyUnlock.covers("com.bank", until, "com.bank", T0 + 1))
    }

    /** Free time, so it runs out on the clock — unlike session time, which was paid for. */
    @Test
    fun `the five minutes runs down in real time`() {
        val until = T0 + EmergencyUnlock.DURATION_SECONDS * 1000
        assertEquals(300L, EmergencyUnlock.remainingSeconds(until, T0))
        assertEquals(120L, EmergencyUnlock.remainingSeconds(until, T0 + 180_000))
        assertEquals(0L, EmergencyUnlock.remainingSeconds(until, T0 + 999_000))
    }

    // --- Two per rolling week ---

    @Test
    fun `a fresh week has two`() {
        assertEquals(2, EmergencyUnlock.usesLeft(emptyList(), T0))
        assertTrue(EmergencyUnlock.canUnlock(emptyList(), T0, hardLockUntil = 0))
    }

    @Test
    fun `spending them runs the count down and then refuses`() {
        val one = EmergencyUnlock.recordUse(emptyList(), T0)
        assertEquals(1, EmergencyUnlock.usesLeft(one, T0))

        val two = EmergencyUnlock.recordUse(one, T0 + HOUR)
        assertEquals(0, EmergencyUnlock.usesLeft(two, T0 + HOUR))
        assertFalse(EmergencyUnlock.canUnlock(two, T0 + HOUR, hardLockUntil = 0))
    }

    /**
     * Rolling, not calendar. A fixed weekly reset would let someone spend two
     * just before it and two more just after.
     */
    @Test
    fun `each use comes back exactly a week after it was spent`() {
        val used = EmergencyUnlock.recordUse(EmergencyUnlock.recordUse(emptyList(), T0), T0 + 6 * HOUR)
        assertEquals(0, EmergencyUnlock.usesLeft(used, T0 + 7 * HOUR))

        // A day later is no longer enough: under the reading lock a daily
        // pair of hatches would be a daily way round it.
        assertEquals(0, EmergencyUnlock.usesLeft(used, T0 + DAY + 1))

        // The first one is a week old: one back.
        assertEquals(1, EmergencyUnlock.usesLeft(used, T0 + WEEK + 1))
        // The second follows six hours later.
        assertEquals(2, EmergencyUnlock.usesLeft(used, T0 + 6 * HOUR + WEEK + 1))
    }

    @Test
    fun `the wait is until the oldest counting use expires`() {
        val used = EmergencyUnlock.recordUse(EmergencyUnlock.recordUse(emptyList(), T0), T0 + 6 * HOUR)
        assertEquals(T0 + WEEK, EmergencyUnlock.nextAvailableAt(used, T0 + 7 * HOUR))
        assertNull(EmergencyUnlock.nextAvailableAt(emptyList(), T0))
        assertNull(EmergencyUnlock.nextAvailableAt(listOf(T0), T0))
    }

    // --- The hard lock ---

    /**
     * A deliberate refusal, not an oversight. A hard lock's entire value is
     * that nothing lifts it; a hatch punching through would make it a setting.
     */
    @Test
    fun `a hard lock refuses the emergency button`() {
        assertFalse(EmergencyUnlock.canUnlock(emptyList(), T0, hardLockUntil = T0 + HOUR))
        assertTrue(EmergencyUnlock.canUnlock(emptyList(), T0, hardLockUntil = T0))
    }

    /**
     * An emergency unlock opens ONE app. It must not also unlock the settings
     * that would let the reader dismantle the gate — that would turn a
     * five-minute hatch into a permanent one.
     */
    @Test
    fun `an emergency unlock does not unlock the rules`() {
        // The hatch writes nothing to today's reading, and canLoosenTheRules
        // reads only that. Stated as a test so a future change that "helpfully"
        // credits a moment of reading has to argue with this.
        val lockedOut = GateState(
            switchedOn = true,
            readTodaySeconds = 0,
            disableAtMillis = 0,
            nowMillis = T0,
        )
        assertFalse(lockedOut.canLoosenTheRules)
        assertFalse(lockedOut.canRemoveBlockedApps)
        // And the unlock itself is still perfectly valid for its own app.
        assertTrue(EmergencyUnlock.covers("com.bank", T0 + 60_000, "com.bank", T0))
    }

    // --- Storage ---

    @Test
    fun `use stamps survive a round trip`() {
        val stamps = listOf(T0, T0 - HOUR)
        assertEquals(stamps.sortedDescending(), EmergencyUnlock.decode(EmergencyUnlock.encode(stamps)))
    }

    @Test
    fun `a corrupt or empty stored value reads as no uses`() {
        assertEquals(emptyList<Long>(), EmergencyUnlock.decode(null))
        assertEquals(emptyList<Long>(), EmergencyUnlock.decode(""))
        assertEquals(emptyList<Long>(), EmergencyUnlock.decode("nonsense,,-4"))
        assertEquals(listOf(T0), EmergencyUnlock.decode("nonsense,$T0"))
        // Nothing readable means the reader is not punished for it.
        assertEquals(2, EmergencyUnlock.usesLeft(EmergencyUnlock.decode("rubbish"), T0))
    }

    /**
     * The stored list must not grow by two a week forever — a preference that
     * only ever gets longer is a leak nobody notices until it is large.
     */
    @Test
    fun `the stored list stays bounded however long the app is used`() {
        var stamps = emptyList<Long>()
        var t = T0
        repeat(500) {
            t += 3 * HOUR
            if (EmergencyUnlock.usesLeft(stamps, t) > 0) stamps = EmergencyUnlock.recordUse(stamps, t)
        }
        assertTrue("stored ${stamps.size} stamps", stamps.size <= EmergencyUnlock.USES_PER_WINDOW)
    }

    @Test
    fun `the shipped numbers are a hatch and not a second economy`() {
        assertEquals(5L * 60, EmergencyUnlock.DURATION_SECONDS)
        assertEquals(2, EmergencyUnlock.USES_PER_WINDOW)
        assertEquals(7L * DAY, EmergencyUnlock.WINDOW_MILLIS)
        // Ten free minutes a WEEK, against a daily target of twenty minutes of
        // reading: the hatch cannot stand in for even one day's reading.
        val freeWeekly = EmergencyUnlock.DURATION_SECONDS * EmergencyUnlock.USES_PER_WINDOW
        assertTrue(freeWeekly < GateState.DEFAULT_DAILY_TARGET_SECONDS)
    }
}

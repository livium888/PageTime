package com.pagetime.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry loop's decisions. The condition these mostly circle is the one that
 * decides whether a block sticks to the app or follows the reader out of it.
 */
class BlockEnforcementPolicyTest {

    private fun shouldShow(
        overlayAttached: Boolean = false,
        current: String? = "com.example.blocked",
        expected: String? = "com.example.blocked",
        accessDenied: Boolean = true,
        seenRecently: Boolean = true,
    ) = BlockEnforcementPolicy.shouldShowOverlay(
        overlayAttached = overlayAttached,
        currentBlockedPackage = current,
        expectedBlockedPackage = expected,
        accessDenied = accessDenied,
        blockedAppSeenRecently = seenRecently,
    )

    // --- Which rule decides access ---

    /**
     * Off means off. There is no second currency to fall back on any more, so
     * a disabled gate denies nothing at all rather than quietly handing the
     * decision to a balance the reader could not see.
     */
    @Test
    fun `with the gate off nothing is denied`() {
        assertFalse(BlockEnforcementPolicy.accessDenied(gateEnabled = false, gateOpen = false))
        assertFalse(BlockEnforcementPolicy.accessDenied(gateEnabled = false, gateOpen = true))
    }

    @Test
    fun `under the gate a shut gate denies`() {
        assertTrue(
            BlockEnforcementPolicy.accessDenied(
                gateEnabled = true,
                gateOpen = false,
            )
        )
    }

    @Test
    fun `under the gate an open one allows`() {
        assertFalse(
            BlockEnforcementPolicy.accessDenied(
                gateEnabled = true,
                gateOpen = true,
            )
        )
    }

    // --- The hard lock, which must only ever get longer ---

    /**
     * A lock that can be shortened is not a lock. The screen disables the
     * buttons while one runs, so this was unreachable from there — but that is
     * exactly how the settings sliders came to be a way out.
     */
    @Test
    fun `a running hard lock cannot be cut short`() {
        val now = 1_000L
        val threeHours = now + 3 * 60 * 60 * 1000
        val thirtyMinutes = now + 30 * 60 * 1000
        assertEquals(
            threeHours,
            BlockEnforcementPolicy.hardLockAfterSetting(threeHours, thirtyMinutes, now)
        )
    }

    @Test
    fun `a running hard lock can be extended`() {
        val now = 1_000L
        val oneHour = now + 60 * 60 * 1000
        val threeHours = now + 3 * 60 * 60 * 1000
        assertEquals(
            threeHours,
            BlockEnforcementPolicy.hardLockAfterSetting(oneHour, threeHours, now)
        )
    }

    @Test
    fun `an expired hard lock is simply replaced`() {
        val now = 10_000_000L
        val expired = now - 1
        val fresh = now + 60_000
        assertEquals(fresh, BlockEnforcementPolicy.hardLockAfterSetting(expired, fresh, now))
        // Including by a shorter one than the expired lock had been.
        assertEquals(fresh, BlockEnforcementPolicy.hardLockAfterSetting(now - 999_999, fresh, now))
    }

    @Test
    fun `no stored lock means the new one stands`() {
        val now = 1_000L
        assertEquals(now + 500, BlockEnforcementPolicy.hardLockAfterSetting(0, now + 500, now))
    }

    // --- The retry loop ---

    @Test
    fun `an attached overlay is never shown again`() {
        assertFalse(shouldShow(overlayAttached = true))
    }

    @Test
    fun `detached overlay retries for the same blocked app at zero`() {
        assertTrue(shouldShow())
    }

    @Test
    fun `different foreground package cannot trigger redraw`() {
        assertFalse(shouldShow(current = "com.example.other"))
    }

    @Test
    fun `an open gate cannot trigger the overlay`() {
        assertFalse(shouldShow(accessDenied = false))
    }

    @Test
    fun `missing current package cannot trigger overlay`() {
        assertFalse(shouldShow(current = null))
    }

    @Test
    fun `a blocked app not seen lately does not get the overlay put back`() {
        // The reported bug. Most launchers expose no window this service can
        // inspect, so pressing Home proves nothing — and "nothing" used to read
        // as "still in the blocked app". The overlay came down with the app and
        // the loop put it straight back, over the home screen.
        assertFalse(shouldShow(seenRecently = false))
    }

    @Test
    fun `a stale sighting cannot be rescued by the other conditions`() {
        // Every other condition here is satisfied by a block that is merely
        // stale: the package still matches, access is still denied, the
        // overlay is still detached. Only the sighting distinguishes a reader
        // sitting in the blocked app from one who has left it.
        assertFalse(
            shouldShow(
                overlayAttached = false,
                current = "com.example.blocked",
                expected = "com.example.blocked",
                accessDenied = true,
                seenRecently = false,
            )
        )
    }

    @Test
    fun `an overlay already up is unaffected by a stale sighting`() {
        // An active block must not be weakened by this. The rule applies only
        // to putting the overlay BACK, which is the moment that coincides with
        // the reader having gone elsewhere.
        assertFalse(shouldShow(overlayAttached = true, seenRecently = false))
        assertFalse(shouldShow(overlayAttached = true, seenRecently = true))
    }
}

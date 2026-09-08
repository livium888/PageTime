package com.pagetime.app.blocker

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

    @Test
    fun `on the balance, any remaining second buys entry`() {
        assertFalse(BlockEnforcementPolicy.accessDenied(false, gateOpen = false, balanceSeconds = 1))
        assertTrue(BlockEnforcementPolicy.accessDenied(false, gateOpen = true, balanceSeconds = 0))
    }

    /**
     * The point of the whole change. Under the gate the balance is not
     * consulted at all — a reader carrying an hour of credit from the old
     * currency who has read nothing today is still shut out.
     */
    @Test
    fun `under the gate the old balance buys nothing`() {
        assertTrue(
            BlockEnforcementPolicy.accessDenied(
                gateEnabled = true,
                gateOpen = false,
                balanceSeconds = 3600,
            )
        )
    }

    @Test
    fun `under the gate an empty balance is irrelevant once the reading is done`() {
        assertFalse(
            BlockEnforcementPolicy.accessDenied(
                gateEnabled = true,
                gateOpen = true,
                balanceSeconds = 0,
            )
        )
    }

    // --- Overrides ---

    @Test
    fun `a hard lock beats a quick-disable in either era`() {
        assertFalse(BlockEnforcementPolicy.graceApplies(false, 100, quickDisableUntil = 900, hardLockUntil = 500))
        assertFalse(BlockEnforcementPolicy.graceApplies(true, 100, quickDisableUntil = 900, hardLockUntil = 500))
    }

    @Test
    fun `quick-disable still works on the balance`() {
        assertTrue(BlockEnforcementPolicy.graceApplies(false, 100, quickDisableUntil = 900, hardLockUntil = 0))
        assertFalse(BlockEnforcementPolicy.graceApplies(false, 1000, quickDisableUntil = 900, hardLockUntil = 0))
    }

    /**
     * A two-hour boundary with a five-minute bypass button beside it is a
     * button, not a boundary.
     */
    @Test
    fun `quick-disable cannot open the gate`() {
        assertFalse(BlockEnforcementPolicy.graceApplies(true, 100, quickDisableUntil = Long.MAX_VALUE, hardLockUntil = 0))
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

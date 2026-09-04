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
        balanceSeconds: Long = 0,
        seenRecently: Boolean = true,
    ) = BlockEnforcementPolicy.shouldShowOverlay(
        overlayAttached = overlayAttached,
        currentBlockedPackage = current,
        expectedBlockedPackage = expected,
        balanceSeconds = balanceSeconds,
        blockedAppSeenRecently = seenRecently,
    )

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
    fun `positive balance cannot trigger overlay`() {
        assertFalse(shouldShow(balanceSeconds = 1))
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
        // stale: the package still matches, the balance is still empty, the
        // overlay is still detached. Only the sighting distinguishes a reader
        // sitting in the blocked app from one who has left it.
        assertFalse(
            shouldShow(
                overlayAttached = false,
                current = "com.example.blocked",
                expected = "com.example.blocked",
                balanceSeconds = 0,
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

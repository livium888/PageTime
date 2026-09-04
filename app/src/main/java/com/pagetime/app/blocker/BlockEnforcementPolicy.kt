package com.pagetime.app.blocker

/** Pure decisions for the block overlay's retry loop. */
object BlockEnforcementPolicy {

    /**
     * An attached overlay must never be shown again: repeating WindowManager
     * operations can cause focus churn and visible flashing. A detached overlay
     * may be retried only while the original blocked package is still current
     * AND the blocked app has recently been seen in front.
     *
     * That last condition is the difference between a block that sticks and a
     * block that follows the reader around. The block used to hold until
     * something proved the user had left — but a great many launchers expose no
     * inspectable window, so pressing Home proves nothing, and "nothing" was
     * read as "still in the blocked app". The overlay came down with the app
     * and this loop put it straight back, over the home screen.
     *
     * Re-showing is the one moment that needs positive evidence, because the
     * overlay being detached is exactly what happens when the user has gone
     * somewhere else. An attached overlay is untouched by this: an active block
     * stays put.
     */
    fun shouldShowOverlay(
        overlayAttached: Boolean,
        currentBlockedPackage: String?,
        expectedBlockedPackage: String?,
        balanceSeconds: Long,
        blockedAppSeenRecently: Boolean
    ): Boolean =
        !overlayAttached &&
            blockedAppSeenRecently &&
            balanceSeconds <= 0 &&
            !currentBlockedPackage.isNullOrBlank() &&
            currentBlockedPackage == expectedBlockedPackage
}

package com.pagetime.app.blocker

/** Pure decisions for the block overlay's retry loop. */
object BlockEnforcementPolicy {

    /**
     * An attached overlay must never be shown again: repeating WindowManager
     * operations can cause focus churn and visible flashing. A detached overlay
     * may be retried only while the original blocked package is still current.
     */
    fun shouldShowOverlay(
        overlayAttached: Boolean,
        currentBlockedPackage: String?,
        expectedBlockedPackage: String?,
        balanceSeconds: Long
    ): Boolean =
        !overlayAttached &&
            balanceSeconds <= 0 &&
            !currentBlockedPackage.isNullOrBlank() &&
            currentBlockedPackage == expectedBlockedPackage
}

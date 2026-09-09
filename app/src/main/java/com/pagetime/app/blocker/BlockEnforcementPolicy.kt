package com.pagetime.app.blocker

/** Pure decisions for the block overlay's retry loop. */
object BlockEnforcementPolicy {

    /**
     * Whether a blocked app must be kept shut right now.
     *
     * Two eras in one function. Under the balance, access was a purchase and
     * this asked whether there was anything left to spend. Under the gate it
     * asks a different question entirely — has the day's reading been done —
     * and the balance has no vote, which is what stops "read one minute,
     * browse one minute" from surviving the change.
     */
    fun accessDenied(
        gateEnabled: Boolean,
        gateOpen: Boolean,
        balanceSeconds: Long,
    ): Boolean = if (gateEnabled) !gateOpen else balanceSeconds <= 0

    /**
     * Whether a user-approved override is lifting the block.
     *
     * A hard lock beats everything, as it always did. The quick-disable grace
     * never applies under the gate: a two-hour boundary with a five-minute
     * bypass button next to it is not a boundary, it is a button.
     *
     * Its buttons have since been deleted from the app entirely, so nothing
     * can set this any more. The check stays because an install upgrading
     * mid-grace still carries a stored expiry, and the honest thing is to let
     * a pause the reader was already given run out rather than cancel it. Once
     * those have expired the parameter is permanently zero.
     *
     * The consequence is worth stating plainly: under the gate the only ways
     * out are reading, or turning the gate off in Settings — which itself
     * takes a day. Neither is reachable from the block screen, which is where
     * someone would look at the moment they least want to be told no.
     */
    fun graceApplies(
        gateEnabled: Boolean,
        nowMillis: Long,
        quickDisableUntil: Long,
        hardLockUntil: Long,
    ): Boolean {
        if (nowMillis < hardLockUntil) return false
        if (gateEnabled) return false
        return nowMillis < quickDisableUntil
    }

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
        /**
         * The answer from [accessDenied], not the balance.
         *
         * This used to take the balance and test it against zero, which under
         * the gate is the wrong question and answers it wrongly: a reader with
         * minutes left over from the old currency and no reading done today
         * would be refused entry by the controller and then never shown the
         * screen saying so, because the balance was positive.
         */
        accessDenied: Boolean,
        blockedAppSeenRecently: Boolean
    ): Boolean =
        !overlayAttached &&
            blockedAppSeenRecently &&
            accessDenied &&
            !currentBlockedPackage.isNullOrBlank() &&
            currentBlockedPackage == expectedBlockedPackage
}

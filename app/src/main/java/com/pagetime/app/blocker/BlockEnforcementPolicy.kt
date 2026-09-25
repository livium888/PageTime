package com.pagetime.app.blocker

/** Pure decisions for the block overlay's retry loop. */
object BlockEnforcementPolicy {

    /**
     * Whether a blocked app must be kept shut right now.
     *
     * One question, where there used to be two. The old browse balance asked
     * whether anything was left to spend; the gate asks whether the reading has
     * been done. Both ran at once, picked between by a switch, and which of the
     * two numbers on screen actually governed anything depended on that switch
     * — which is what made the app impossible to reason about.
     *
     * Only the gate remains. With it off nothing is blocked at all, rather than
     * falling back to a second currency: an off switch should mean off.
     */
    fun accessDenied(
        gateEnabled: Boolean,
        gateOpen: Boolean,
    ): Boolean = gateEnabled && !gateOpen

    /**
     * What a hard lock's end time becomes when one is set.
     *
     * A hard lock that can be SHORTENED is not a hard lock. The buttons are
     * disabled while one runs, so from the screen this never arises — but that
     * is exactly the shape of hole the settings sliders turned out to be, and
     * a rule enforced only by a disabled button is enforced only for callers
     * that go through that button.
     *
     * While a lock is running, a new one can only push the end further out.
     * Once it has expired, any new lock replaces it.
     */
    fun hardLockAfterSetting(currentUntil: Long, proposedUntil: Long, nowMillis: Long): Long =
        if (nowMillis < currentUntil) maxOf(currentUntil, proposedUntil) else proposedUntil

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

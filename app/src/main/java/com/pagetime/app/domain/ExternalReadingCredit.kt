package com.pagetime.app.domain

/**
 * How foreground time in a trusted external reading app (Kindle) converts to
 * PageTime's own reading credit.
 *
 * WHY THIS IS DISCOUNTED AT ALL
 *
 * [com.pagetime.app.ui.screens.reader.ReadingGuard] can tell a page turning
 * from a phone merely left on — it watches actual scroll position against a
 * plausible pace. Nothing about a foreign app's screen offers that; all
 * [com.pagetime.app.data.usage.ExternalReadingTracker] can ever report is
 * "Kindle held the foreground with the screen interactive", which a phone
 * propped up and left alone would also produce. [CONVERSION_RATE] does not
 * close that gap — nothing can, without Kindle's own cooperation — it bounds
 * what it costs: half rate means a false report is worth half of what a false
 * report would otherwise be worth, for the same reason
 * [BalanceManager.earnFromFlashcard]'s daily cap exists despite flashcards
 * being graded honestly by FSRS. See [DailyEarningCap] for the other half of
 * that bound.
 */
object ExternalReadingCredit {

    /** One credited second of reading for every two seconds Kindle held the foreground. */
    const val CONVERSION_RATE = 0.5f

    fun creditedSeconds(foregroundSeconds: Long): Long =
        (foregroundSeconds * CONVERSION_RATE).toLong().coerceAtLeast(0L)
}

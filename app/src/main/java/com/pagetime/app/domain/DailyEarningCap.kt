package com.pagetime.app.domain

/**
 * How much of today's allowance a per-day-capped reward still has left to pay.
 *
 * Shared by every reward that is worth limiting per day rather than per use:
 * [BalanceManager.earnFromFlashcard] (a flashcard is a few seconds of work no
 * matter how many are due, which beats reading's hourly rate unless something
 * limits it) and [BalanceManager.earnFromExternalReading] (foreground time in
 * a trusted external reader cannot be verified the way a page turn in
 * PageTime's own reader can, so what it can cost per day is bounded instead).
 * Both problems are the same shape — a source that pays real credit on a
 * signal weaker than [ReadingGuard]'s — so they share one answer.
 */
object DailyEarningCap {

    /** Yesterday's tally (or no tally at all) doesn't count against today's cap. */
    fun earnedSoFar(storedEpochDay: Long, today: Long, storedSeconds: Long): Long =
        if (storedEpochDay == today) storedSeconds else 0L

    /** How many of [requestedSeconds] the cap still allows, given what's already been earned today. */
    fun payable(requestedSeconds: Long, earnedSoFarToday: Long, capSeconds: Long): Long =
        (capSeconds - earnedSoFarToday).coerceIn(0L, requestedSeconds)
}

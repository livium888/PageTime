package com.pagetime.app.domain

/**
 * How much of today's flashcard/Anki reward [BalanceManager.earnFromFlashcard]
 * is still allowed to pay, once a daily cap is in play.
 *
 * Reading pays for as long as the reader keeps reading; a flashcard is a few
 * seconds of work no matter how many are due, which makes it a far better
 * hourly rate than reading unless something limits it. Capping what
 * flashcards can earn per day is what keeps them a quick top-up — worth
 * reaching for when the reader is short on time — rather than a way to fund
 * a whole day's browsing without ever opening a book.
 */
object FlashcardDailyCap {

    /** Yesterday's tally (or no tally at all) doesn't count against today's cap. */
    fun earnedSoFar(storedEpochDay: Long, today: Long, storedSeconds: Long): Long =
        if (storedEpochDay == today) storedSeconds else 0L

    /** How many of [requestedSeconds] the cap still allows, given what's already been earned today. */
    fun payable(requestedSeconds: Long, earnedSoFarToday: Long, capSeconds: Long): Long =
        (capSeconds - earnedSoFarToday).coerceIn(0L, requestedSeconds)
}

package com.pagetime.app.data

import com.pagetime.app.data.local.BookEntity

/**
 * Picks the one book worth mentioning today, and the honest reason why —
 * never an invented one, never a mandate. See [LibrarianFacts] for how a
 * pick becomes an actual sentence, and [LibrarianSuggester] for how often
 * this runs.
 */
object LibrarianPicks {

    enum class Reason { NEVER_OPENED, ALMOST_DONE }

    data class Pick(val book: BookEntity, val reason: Reason)

    private val ALMOST_DONE_RANGE = 0.85f..0.99f

    /**
     * Finishing something already underway beats starting something new —
     * closer to done is closer to a real payoff, and it is real: this reader
     * chose this book already. Falls back to the oldest book never opened at
     * all if nothing is close to finished. Null when neither exists (an
     * empty library, or every book already finished or barely started),
     * rather than forcing a suggestion that isn't actually true of anything.
     */
    fun choose(books: List<BookEntity>): Pick? {
        books.filter { it.scrollProgress in ALMOST_DONE_RANGE }
            .maxByOrNull { it.scrollProgress }
            ?.let { return Pick(it, Reason.ALMOST_DONE) }

        return books.filter { it.totalReadingSeconds == 0L && it.scrollProgress == 0f }
            .minByOrNull { it.addedAt }
            ?.let { Pick(it, Reason.NEVER_OPENED) }
    }

    /** True once a day has passed since the stored suggestion, or none exists yet. */
    fun needsRefresh(shownEpochDay: Long?, today: Long): Boolean =
        shownEpochDay == null || shownEpochDay != today
}

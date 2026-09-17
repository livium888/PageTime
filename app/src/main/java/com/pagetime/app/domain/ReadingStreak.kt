package com.pagetime.app.domain

/**
 * "Don't break the chain": a streak counts local calendar days that had at
 * least one reading or flashcard credit, not total minutes and not a rolling
 * 24-hour window. [activeDays] are epoch-day numbers, matching
 * `java.time.LocalDate.toEpochDay()`, so callers can compute "today" the same
 * way regardless of time zone or DST.
 */
object ReadingStreak {

    /** True if [todayEpochDay] itself is one of the active days. */
    fun didToday(activeDays: Set<Long>, todayEpochDay: Long): Boolean =
        todayEpochDay in activeDays

    /**
     * Consecutive active days ending at [todayEpochDay], counting backward.
     * A single day read is a streak of 1, not 0 — today counts. The first
     * day with nothing in it breaks the count immediately; there is no
     * forgiveness built in here, that is [needsNeverMissTwiceNudge]'s job.
     */
    fun currentStreak(activeDays: Set<Long>, todayEpochDay: Long): Int {
        var day = todayEpochDay
        var streak = 0
        while (day in activeDays) {
            streak++
            day--
        }
        return streak
    }

    /**
     * True exactly when yesterday was missed and today hasn't happened yet —
     * the one moment "never miss twice" applies. Requires at least one active
     * day before yesterday, so a brand-new reader with no history at all
     * never sees it: there is nothing to miss on day one.
     */
    fun needsNeverMissTwiceNudge(activeDays: Set<Long>, todayEpochDay: Long): Boolean {
        val yesterday = todayEpochDay - 1
        if (yesterday in activeDays) return false
        if (todayEpochDay in activeDays) return false
        return activeDays.any { it < yesterday }
    }
}

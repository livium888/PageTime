package com.pagetime.app.domain

/**
 * The escape hatch: one app, five minutes, twice a day.
 *
 * WHY THERE IS ONE AT ALL
 *
 * Every other rule in this app is built to have no way round it, and that is
 * correct right up until the reader needs a two-factor code from their banking
 * app. A blocker with no valve does not get respected, it gets uninstalled —
 * and an uninstalled blocker blocks nothing at all. So the valve exists, and
 * the whole design problem is making it small enough that it cannot become the
 * front door.
 *
 * ONE APP, NOT ALL OF THEM
 *
 * This is the part that makes it safe. The block screen already knows exactly
 * which app the reader just tried to open, so the unlock covers that package
 * and nothing else. "I need my banking app" is not a reason for Instagram to
 * open.
 *
 * It also changes what the button IS. A general bypass is an admission that
 * the rules can be waived; a per-app unlock is just "let me into this one
 * thing", which is what an emergency actually is. The blast radius is one app
 * for five minutes rather than the whole arrangement.
 *
 * FIVE MINUTES, BECAUSE EMERGENCIES ARE SHORT
 *
 * A two-factor code is thirty seconds. A message is a minute. A taxi is three.
 * Fifteen minutes twice a day would be thirty free minutes — exactly one
 * session, which costs two hours of reading — so it would not dent the
 * economy, it would replace it. Five is enough to do the thing and useless for
 * scrolling.
 *
 * A WALL CLOCK HERE, UNLIKE A SESSION
 *
 * Session time is metered because the reader PAID two hours for it and it must
 * not evaporate while the phone is face-down. Emergency time is free, and free
 * time that could be stretched across a whole day by putting the phone down
 * would be a second currency. So this one does run out on the clock, and the
 * difference in reasoning is the difference in who paid.
 *
 * TWICE PER ROLLING DAY
 *
 * Each use comes back twenty-four hours after it was spent, rather than both
 * resetting at midnight — a midnight reset invites spending two at 11:55pm and
 * two more at midnight, which is twenty minutes in ten.
 */
object EmergencyUnlock {

    const val DURATION_SECONDS = 5L * 60

    const val USES_PER_WINDOW = 2

    const val WINDOW_MILLIS = 24L * 60 * 60 * 1000

    /** Uses inside the rolling window, newest first. Anything older has expired. */
    fun usesInWindow(stamps: List<Long>, nowMillis: Long): List<Long> =
        stamps.filter { it > 0 && nowMillis - it < WINDOW_MILLIS }.sortedDescending()

    fun usesLeft(stamps: List<Long>, nowMillis: Long): Int =
        (USES_PER_WINDOW - usesInWindow(stamps, nowMillis).size).coerceAtLeast(0)

    /**
     * When the next use becomes available, or null if one is available now.
     *
     * The oldest use inside the window is the one that expires first, so that
     * is the one the reader is waiting on.
     */
    fun nextAvailableAt(stamps: List<Long>, nowMillis: Long): Long? {
        val used = usesInWindow(stamps, nowMillis)
        if (used.size < USES_PER_WINDOW) return null
        val oldestCounting = used.take(USES_PER_WINDOW).last()
        return oldestCounting + WINDOW_MILLIS
    }

    /**
     * Whether the button may be pressed.
     *
     * A hard lock refuses it. That is a real decision and not an oversight: a
     * hard lock is a promise the reader made to themselves for a fixed time,
     * and its entire value is that nothing lifts it — a hatch that punched
     * through would make it just another setting. It costs less than it
     * sounds, because a hard lock only covers apps the reader chose to block;
     * calls, messages, maps and the camera were never blocked, so a genuine
     * safety emergency does not route through any of this.
     */
    fun canUnlock(stamps: List<Long>, nowMillis: Long, hardLockUntil: Long): Boolean =
        nowMillis >= hardLockUntil && usesLeft(stamps, nowMillis) > 0

    /**
     * Whether an unlock is currently covering [packageName].
     *
     * Blank or mismatched package means no: an unlock granted for one app must
     * never quietly cover the next one the reader opens, which is exactly what
     * a general bypass would do.
     */
    fun covers(
        unlockedPackage: String?,
        untilMillis: Long,
        packageName: String?,
        nowMillis: Long,
    ): Boolean =
        nowMillis < untilMillis &&
            !unlockedPackage.isNullOrBlank() &&
            !packageName.isNullOrBlank() &&
            unlockedPackage == packageName

    /** Seconds left on the current unlock, for the countdown. */
    fun remainingSeconds(untilMillis: Long, nowMillis: Long): Long =
        ((untilMillis - nowMillis) / 1000).coerceAtLeast(0L)

    /**
     * The use list after spending one, with expired entries dropped.
     *
     * Pruning on write keeps the stored value bounded — otherwise it grows by
     * two a day forever, and a preference that only ever gets longer is a slow
     * leak nobody notices until it is large.
     */
    fun recordUse(stamps: List<Long>, nowMillis: Long): List<Long> =
        (usesInWindow(stamps, nowMillis) + nowMillis).sortedDescending().take(USES_PER_WINDOW)

    // --- Storage ---
    //
    // A comma-separated string rather than a set, because order and
    // duplicates both matter here and a preference Set gives neither.

    fun encode(stamps: List<Long>): String = stamps.filter { it > 0 }.joinToString(",")

    fun decode(raw: String?): List<Long> =
        raw?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.filter { it > 0 }
            ?: emptyList()
}

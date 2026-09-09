package com.pagetime.app.data

import com.pagetime.app.data.local.PackageTotal
import com.pagetime.app.data.local.UsageEventDao
import com.pagetime.app.data.local.UsageEventEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Append-only ledger of how browse time was earned and spent. This is the
 * auditable memory of the app: "earned X reading, spent Y in <app>, blocked at
 * zero Z times" survives process death because it lives in Room.
 */
class UsageRepository(
    private val dao: UsageEventDao,
    /**
     * Wall clock, injectable so the rolling window can be tested.
     *
     * Every window in here is "now minus a day", and a test that cannot move
     * "now" can only assert that the number came out — not that the window
     * moved with it, which is the property that was wrong.
     */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        const val TYPE_EARNED = "EARNED"
        const val TYPE_SPENT = "SPENT"
        const val TYPE_BLOCKED = "BLOCKED"
        /** Retroactive charge applied from UsageStats after the service missed time. */
        const val TYPE_RECONCILED = "RECONCILED"

        /**
         * A session was opened: reading credit spent for a window of app time.
         *
         * The one row that says the bargain was actually struck. Reading rows
         * say what was earned and spend rows say what a particular app took;
         * neither answers "how many times this week did I trade two hours for
         * thirty minutes", which is the question the whole mechanism exists to
         * make askable.
         */
        const val TYPE_SESSION = "SESSION"

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * How often the rolling window is re-cut.
         *
         * Only matters in one direction. Time ENTERING the window is picked up
         * immediately, because Room re-runs the query whenever a row is
         * inserted and the reader's ticker inserts every few seconds. This tick
         * is for time LEAVING it — reading that has aged past twenty-four hours
         * — which nothing in the database signals, since nothing happens. A
         * minute of slack on a gate measured in hours is not worth waking the
         * process more often than this.
         */
        private const val WINDOW_TICK_MS = 60_000L
    }

    suspend fun log(type: String, packageName: String?, seconds: Long) {
        dao.insert(
            UsageEventEntity(
                timestamp = now(),
                type = type,
                packageName = packageName,
                seconds = seconds
            )
        )
    }

    /**
     * Records a spend session (live ticker or UsageStats reconciliation) with its
     * exact wall-clock window so later reconciles can attribute time without
     * double-charging.
     */
    suspend fun logSpent(packageName: String, seconds: Long, windowStart: Long, windowEnd: Long) {
        dao.insert(
            UsageEventEntity(
                timestamp = now(),
                type = TYPE_SPENT,
                packageName = packageName,
                seconds = seconds,
                windowStart = windowStart,
                windowEnd = windowEnd
            )
        )
    }

    fun recent(limit: Int = 200): Flow<List<UsageEventEntity>> = dao.observeRecent(limit)

    fun earnedSince(since: Long): Flow<Long> =
        dao.sumSince(TYPE_EARNED, since)

    fun spentSince(since: Long): Flow<Map<String, Long>> =
        dao.observeRecent(Int.MAX_VALUE).map { events ->
            events.filter { it.type in SPEND_TYPES && it.timestamp >= since }
                .groupingBy { it.packageName ?: "unknown" }
                .fold(0L) { acc, e -> acc + e.seconds }
        }

    fun spentToday(): Flow<Long> = dao.sumOfTypesSince(SPEND_TYPES, now() - DAY_MS)

    fun liveSpentToday(): Flow<Long> =
        dao.sumSince(TYPE_SPENT, now() - DAY_MS)

    fun reconciledToday(): Flow<Long> =
        dao.sumSince(TYPE_RECONCILED, now() - DAY_MS)

    fun blockedToday(): Flow<Long> =
        dao.countSince(TYPE_BLOCKED, now() - DAY_MS)

    /** Blocked counts per app over the last day, most-blocked first. */
    fun blockedCountsByPackageToday(): Flow<List<PackageTotal>> =
        dao.blockedCountsByPackageSince(now() - DAY_MS)

    /** Browse-seconds burned per app over the last day, most-burned first. */
    fun spentSecondsByPackageToday(): Flow<List<PackageTotal>> =
        dao.spentSecondsByPackageSince(now() - DAY_MS)

    fun earnedToday(): Flow<Long> = dao.sumSince(TYPE_EARNED, now() - DAY_MS)

    /**
     * Creditable reading in the last twenty-four hours, on a window that
     * actually moves.
     *
     * [earnedToday] freezes its window: the `since` argument is computed once,
     * when the flow is built, and Room then answers that same question forever.
     * For a screen that is opened and closed the difference never shows. For
     * the access gate, which is collected for the life of the process, it is
     * the difference between "two hours in the last day" and "two hours since
     * whenever the blocker last started" — a gate that, once opened, could
     * never close again.
     *
     * Rolling rather than calendar-day on purpose: a midnight reset invites
     * banking minutes at 11:55pm for a day that has not started, and strands
     * anyone reading across it.
     *
     * The access gate does NOT use this. It spends a credit counter, which is
     * a different question — what you have left, rather than what you did.
     * This is the honest report of the day, shown next to the counter so the
     * two can be compared.
     */
    fun readingInLastDay(tickMillis: Long = WINDOW_TICK_MS): Flow<Long> =
        rollingDaySum(TYPE_EARNED, tickMillis)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rollingDaySum(type: String, tickMillis: Long): Flow<Long> =
        windowStarts(tickMillis).flatMapLatest { since -> dao.sumSince(type, since) }

    /** Emits the start of the window now, and again every [tickMillis]. */
    private fun windowStarts(tickMillis: Long): Flow<Long> = flow {
        while (true) {
            emit(now() - DAY_MS)
            delay(tickMillis)
        }
    }

    /** Live + reconciled spend rows with windows overlapping [from], for the reconciler. */
    suspend fun spentWithWindows(from: Long): List<UsageEventEntity> =
        dao.spentWithWindows(TYPE_SPENT, from)

    private val SPEND_TYPES = listOf(TYPE_SPENT, TYPE_RECONCILED)

    suspend fun prune(keepDays: Int = 30) {
        dao.pruneOlderThan(now() - keepDays * DAY_MS)
    }
}

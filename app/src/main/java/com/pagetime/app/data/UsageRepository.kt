package com.pagetime.app.data

import com.pagetime.app.data.local.UsageEventDao
import com.pagetime.app.data.local.UsageEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Append-only ledger of how browse time was earned and spent. This is the
 * auditable memory of the app: "earned X reading, spent Y in <app>, blocked at
 * zero Z times" survives process death because it lives in Room.
 */
class UsageRepository(private val dao: UsageEventDao) {

    companion object {
        const val TYPE_EARNED = "EARNED"
        const val TYPE_SPENT = "SPENT"
        const val TYPE_BLOCKED = "BLOCKED"
        /** Retroactive charge applied from UsageStats after the service missed time. */
        const val TYPE_RECONCILED = "RECONCILED"

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    suspend fun log(type: String, packageName: String?, seconds: Long) {
        dao.insert(
            UsageEventEntity(
                timestamp = System.currentTimeMillis(),
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
                timestamp = System.currentTimeMillis(),
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

    fun spentToday(): Flow<Long> = dao.sumOfTypesSince(SPEND_TYPES, System.currentTimeMillis() - DAY_MS)

    fun liveSpentToday(): Flow<Long> =
        dao.sumSince(TYPE_SPENT, System.currentTimeMillis() - DAY_MS)

    fun reconciledToday(): Flow<Long> =
        dao.sumSince(TYPE_RECONCILED, System.currentTimeMillis() - DAY_MS)

    fun blockedToday(): Flow<Long> =
        dao.countSince(TYPE_BLOCKED, System.currentTimeMillis() - DAY_MS)

    fun earnedToday(): Flow<Long> = dao.sumSince(TYPE_EARNED, System.currentTimeMillis() - DAY_MS)

    /** Live + reconciled spend rows with windows overlapping [from], for the reconciler. */
    suspend fun spentWithWindows(from: Long): List<UsageEventEntity> =
        dao.spentWithWindows(TYPE_SPENT, from)

    private val SPEND_TYPES = listOf(TYPE_SPENT, TYPE_RECONCILED)

    suspend fun prune(keepDays: Int = 30) {
        dao.pruneOlderThan(System.currentTimeMillis() - keepDays * DAY_MS)
    }
}

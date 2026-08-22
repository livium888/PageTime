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

    fun recent(limit: Int = 200): Flow<List<UsageEventEntity>> = dao.observeRecent(limit)

    fun earnedSince(since: Long): Flow<Long> =
        dao.sumSince(TYPE_EARNED, since)

    fun spentSince(since: Long): Flow<Map<String, Long>> =
        dao.observeRecent(Int.MAX_VALUE).map { events ->
            events.filter { it.type == TYPE_SPENT && it.timestamp >= since }
                .groupingBy { it.packageName ?: "unknown" }
                .fold(0L) { acc, e -> acc + e.seconds }
        }

    fun spentToday(): Flow<Long> = dao.sumSince(TYPE_SPENT, System.currentTimeMillis() - DAY_MS)

    fun earnedToday(): Flow<Long> = dao.sumSince(TYPE_EARNED, System.currentTimeMillis() - DAY_MS)

    suspend fun prune(keepDays: Int = 30) {
        dao.pruneOlderThan(System.currentTimeMillis() - keepDays * DAY_MS)
    }
}

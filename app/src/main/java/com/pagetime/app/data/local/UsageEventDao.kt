package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageEventDao {

    @Insert
    suspend fun insert(event: UsageEventEntity)

    @Query("SELECT * FROM usage_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<UsageEventEntity>>

    @Query(
        "SELECT COALESCE(SUM(seconds), 0) FROM usage_events " +
            "WHERE type = :type AND timestamp >= :since"
    )
    fun sumSince(type: String, since: Long): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(seconds), 0) FROM usage_events " +
            "WHERE type IN (:types) AND timestamp >= :since"
    )
    fun sumOfTypesSince(types: List<String>, since: Long): Flow<Long>

    /**
     * Live spend sessions (and reconciled sweeps) with wall-clock windows,
     * used by the UsageStats reconciler to avoid double-charging.
     */
    @Query(
        "SELECT * FROM usage_events WHERE type = :type " +
            "AND windowStart IS NOT NULL AND windowEnd IS NOT NULL " +
            "AND windowEnd >= :from"
    )
    suspend fun spentWithWindows(type: String, from: Long): List<UsageEventEntity>

    @Query("DELETE FROM usage_events WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

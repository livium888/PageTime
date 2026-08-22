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

    @Query("DELETE FROM usage_events WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiUsageDao {
    @Insert
    suspend fun insert(event: AiUsageEntity): Long

    @Query(
        "UPDATE ai_usage_events SET status = :status, outputItems = :outputItems, " +
            "secondaryItems = :secondaryItems, completedAt = :completedAt WHERE id = :id"
    )
    suspend fun complete(
        id: Long,
        status: String,
        outputItems: Int,
        secondaryItems: Int,
        completedAt: Long
    )

    @Query("SELECT * FROM ai_usage_events ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AiUsageEntity>>
}

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

    /**
     * The measured cost of one request.
     *
     * A separate write from [complete] because it is separately optional: a
     * request can succeed and report no usage, and the row should say so by
     * leaving these null rather than by storing zeroes.
     */
    @Query(
        "UPDATE ai_usage_events SET promptTokens = :promptTokens, " +
            "outputTokens = :outputTokens, thinkingTokens = :thinkingTokens, " +
            "cachedTokens = :cachedTokens, totalTokens = :totalTokens WHERE id = :id"
    )
    suspend fun recordTokens(
        id: Long,
        promptTokens: Int,
        outputTokens: Int,
        thinkingTokens: Int,
        cachedTokens: Int,
        totalTokens: Int
    )
}

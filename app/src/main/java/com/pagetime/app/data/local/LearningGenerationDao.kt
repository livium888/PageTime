package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningGenerationDao {
    @Query("SELECT * FROM learning_generations WHERE bookId = :bookId AND generationKey = :generationKey")
    suspend fun get(bookId: String, generationKey: String): LearningGenerationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(generation: LearningGenerationEntity): Long

    @Query("UPDATE learning_generations SET status = :status, cardCount = :cardCount, updatedAt = :updatedAt WHERE bookId = :bookId AND generationKey = :generationKey")
    suspend fun complete(
        bookId: String,
        generationKey: String,
        status: String,
        cardCount: Int,
        updatedAt: Long
    )

    @Query("DELETE FROM learning_generations WHERE bookId = :bookId AND generationKey = :generationKey")
    suspend fun release(bookId: String, generationKey: String)
}

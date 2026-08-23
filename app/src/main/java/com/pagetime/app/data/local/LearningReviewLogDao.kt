package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningReviewLogDao {
    @Insert
    suspend fun insert(log: LearningReviewLogEntity)

    @Query("SELECT * FROM learning_review_logs WHERE cardId = :cardId ORDER BY reviewedAt DESC")
    fun observeForCard(cardId: String): Flow<List<LearningReviewLogEntity>>

    @Query("SELECT * FROM learning_review_logs ORDER BY reviewedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LearningReviewLogEntity>>

    @Query("SELECT COUNT(*) FROM learning_review_logs WHERE cardId = :cardId AND rating >= 3")
    suspend fun successfulReviews(cardId: String): Int

    @Query("SELECT COUNT(*) FROM learning_review_logs WHERE bookId = :bookId AND rating >= 3")
    fun observeSuccessfulReviewsForBook(bookId: String): Flow<Int>
}

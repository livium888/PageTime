package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExplanationDao {
    @Query("SELECT * FROM explanations WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: String): Flow<List<ExplanationEntity>>

    @Query("SELECT * FROM explanations WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY createdAt DESC")
    suspend fun getForChapter(bookId: String, chapterIndex: Int): List<ExplanationEntity>

    @Query("SELECT * FROM explanations WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getAllForBook(bookId: String): List<ExplanationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(explanation: ExplanationEntity)

    @Query("DELETE FROM explanations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM explanations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Concepts with a good score (≥ 3.5 average) are considered "mastered". */
    @Query("SELECT COUNT(*) FROM explanations WHERE bookId = :bookId AND overallScore >= 3.5")
    suspend fun countMastered(bookId: String): Int

    @Query("SELECT COUNT(DISTINCT conceptLabel) FROM explanations WHERE bookId = :bookId")
    suspend fun countConceptsExplained(bookId: String): Int

    /** Get the best (highest-scoring) explanation for each concept. */
    @Query("SELECT * FROM explanations WHERE bookId = :bookId AND conceptLabel = :conceptLabel ORDER BY overallScore DESC LIMIT 1")
    suspend fun bestForConcept(bookId: String, conceptLabel: String): ExplanationEntity?
}

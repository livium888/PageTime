package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningCardDao {
    @Query("SELECT * FROM learning_cards ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LearningCardEntity>>

    @Query("SELECT * FROM learning_cards WHERE id = :id")
    suspend fun getById(id: String): LearningCardEntity?

    @Query("SELECT * FROM learning_cards WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<LearningCardEntity>>

    @Query("SELECT * FROM learning_cards WHERE bookId = :bookId ORDER BY updatedAt DESC")
    suspend fun getForBook(bookId: String): List<LearningCardEntity>

    @Query("SELECT * FROM learning_cards ORDER BY updatedAt DESC")
    suspend fun getAll(): List<LearningCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: LearningCardEntity)

    @Query("DELETE FROM learning_cards WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM learning_cards WHERE bookId = :bookId AND generationKey = :generationKey")
    suspend fun deleteByGenerationKey(bookId: String, generationKey: String)

    @Query("SELECT COUNT(*) FROM learning_cards")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM learning_cards WHERE bookId = :bookId AND generationKey = :generationKey")
    suspend fun countByGenerationKey(bookId: String, generationKey: String): Int
}

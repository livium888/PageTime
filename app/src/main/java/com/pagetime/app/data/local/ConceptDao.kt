package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConceptDao {
    @Query("SELECT * FROM concepts WHERE bookId = :bookId ORDER BY mentionCount DESC, updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<ConceptEntity>>

    @Query("SELECT * FROM concepts WHERE bookId = :bookId ORDER BY mentionCount DESC, updatedAt DESC")
    suspend fun getForBook(bookId: String): List<ConceptEntity>

    @Query("SELECT * FROM concepts WHERE bookId = :bookId AND normalizedLabel = :normalizedLabel LIMIT 1")
    suspend fun getByNormalizedLabel(bookId: String, normalizedLabel: String): ConceptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(concept: ConceptEntity)

    @Query("DELETE FROM concepts WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConceptRelationshipDao {
    @Query("SELECT * FROM concept_relationships WHERE bookId = :bookId ORDER BY confidence DESC, updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<ConceptRelationshipEntity>>

    @Query("SELECT * FROM concept_relationships WHERE bookId = :bookId ORDER BY confidence DESC, updatedAt DESC")
    suspend fun getForBook(bookId: String): List<ConceptRelationshipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: ConceptRelationshipEntity)

    @Query("DELETE FROM concept_relationships WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

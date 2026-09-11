package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TextHighlightDao {

    @Query("SELECT * FROM text_highlights WHERE bookId = :bookId ORDER BY startOffset ASC, createdAt ASC")
    fun observeForBook(bookId: String): Flow<List<TextHighlightEntity>>

    @Query("SELECT * FROM text_highlights WHERE id = :id LIMIT 1")
    suspend fun get(id: String): TextHighlightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(highlight: TextHighlightEntity)

    @Query("DELETE FROM text_highlights WHERE id = :id")
    suspend fun delete(id: String)
}
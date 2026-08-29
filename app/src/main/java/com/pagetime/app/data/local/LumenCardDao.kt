package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LumenCardDao {
    @Query("SELECT * FROM lumen_cards ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LumenCardEntity>>

    @Query("SELECT * FROM lumen_cards WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<LumenCardEntity>>

    @Query("SELECT * FROM lumen_cards WHERE id = :id LIMIT 1")
    suspend fun get(id: String): LumenCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: LumenCardEntity)

    @Query("DELETE FROM lumen_cards WHERE id = :id")
    suspend fun delete(id: String)
}

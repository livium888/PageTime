package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PagemarkDao {

    @Query("SELECT * FROM pagemarks WHERE bookId = :bookId ORDER BY createdAt ASC")
    fun observeForBook(bookId: String): Flow<List<PagemarkEntity>>

    /** Every chunk, for the reading queue. */
    @Query("SELECT * FROM pagemarks")
    fun observeAll(): Flow<List<PagemarkEntity>>

    @Query("SELECT * FROM pagemarks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PagemarkEntity?

    /** The chunk the reader is currently working through, if any. */
    @Query("SELECT * FROM pagemarks WHERE bookId = :bookId AND state = 'READING' LIMIT 1")
    suspend fun openChunk(bookId: String): PagemarkEntity?

    /** Chunks whose re-read is due, newest-due first. */
    @Query("SELECT * FROM pagemarks WHERE dueAt IS NOT NULL AND dueAt <= :now ORDER BY dueAt ASC")
    fun observeDue(now: Long): Flow<List<PagemarkEntity>>

    /** Chunks whose re-read is due, for a review sitting. A chunk mid-read is not due. */
    @Query("SELECT * FROM pagemarks WHERE dueAt IS NOT NULL AND dueAt <= :now AND state != 'READING' ORDER BY dueAt ASC")
    suspend fun dueChunks(now: Long): List<PagemarkEntity>

    @Query("SELECT COUNT(*) FROM pagemarks WHERE dueAt IS NOT NULL AND dueAt <= :now")
    fun observeDueCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM pagemarks WHERE bookId = :bookId")
    suspend fun countForBook(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pagemark: PagemarkEntity)

    @Query("DELETE FROM pagemarks WHERE id = :id")
    suspend fun delete(id: String)
}
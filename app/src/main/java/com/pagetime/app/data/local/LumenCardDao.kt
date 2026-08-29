package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LumenCardDao {
    @Query("SELECT * FROM lumen_cards ORDER BY box ASC, indexNumber ASC, updatedAt DESC")
    fun observeAll(): Flow<List<LumenCardEntity>>

    @Query("SELECT * FROM lumen_cards WHERE bookId = :bookId ORDER BY box ASC, indexNumber ASC, updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<LumenCardEntity>>

    @Query("SELECT * FROM lumen_cards WHERE box = :box ORDER BY indexNumber ASC, updatedAt DESC")
    fun observeBox(box: Int): Flow<List<LumenCardEntity>>

    @Query("SELECT * FROM lumen_cards WHERE id = :id LIMIT 1")
    suspend fun get(id: String): LumenCardEntity?

    @Query("SELECT * FROM lumen_cards WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<LumenCardEntity>

    /** Highest existing index number in a box, for assigning the next address. */
    @Query("SELECT indexNumber FROM lumen_cards WHERE box = :box")
    suspend fun indexNumbersInBox(box: Int): List<String>

    @Query("SELECT MIN(box) FROM lumen_cards")
    suspend fun minBox(): Int?

    @Query("SELECT MAX(box) FROM lumen_cards")
    suspend fun maxBox(): Int?

    @Query("SELECT COUNT(*) FROM lumen_cards WHERE dueAt IS NOT NULL AND dueAt <= :now")
    fun observeDueCount(now: Long): Flow<Int>

    @Query("SELECT * FROM lumen_cards WHERE dueAt IS NOT NULL AND dueAt <= :now ORDER BY dueAt ASC LIMIT :limit")
    suspend fun dueCards(now: Long, limit: Int): List<LumenCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: LumenCardEntity)

    @Query("DELETE FROM lumen_cards WHERE id = :id")
    suspend fun delete(id: String)
}

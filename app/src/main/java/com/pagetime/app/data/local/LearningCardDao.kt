package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The DAO learning_cards has been waiting for.
 *
 * The table, its indices and its columns for cloze, multiple choice and a
 * validated source quote were all declared when the app was written. Nothing
 * ever read or wrote it, so every comprehension prompt the schema anticipated
 * has been impossible to store.
 */
@Dao
interface LearningCardDao {

    /**
     * Prompts the reader has not judged yet, in the order they appear in the
     * chapter — which is the order they will be met while reading.
     */
    @Query(
        "SELECT * FROM learning_cards " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND status = 'pending' " +
            "ORDER BY sourceFraction ASC"
    )
    suspend fun pendingForChapter(bookId: String, chapterIndex: Int): List<LearningCardEntity>

    /** Whether this chapter has already been generated, whatever came of it. */
    @Query(
        "SELECT COUNT(*) FROM learning_cards " +
            "WHERE bookId = :bookId AND generationKey = :generationKey"
    )
    suspend fun countForGeneration(bookId: String, generationKey: String): Int

    /**
     * Kept cards that are due.
     *
     * Pending and skipped rows are excluded in SQL rather than filtered later:
     * a prompt nobody accepted must never reach a review session, and a rule
     * that important belongs in the query.
     */
    @Query(
        "SELECT * FROM learning_cards " +
            "WHERE status = 'kept' AND dueAt IS NOT NULL AND dueAt <= :now " +
            "ORDER BY dueAt ASC LIMIT :limit"
    )
    suspend fun dueCards(now: Long, limit: Int): List<LearningCardEntity>

    @Query(
        "SELECT COUNT(*) FROM learning_cards " +
            "WHERE status = 'kept' AND dueAt IS NOT NULL AND dueAt <= :now"
    )
    fun observeDueCount(now: Long): Flow<Int>

    @Query("SELECT * FROM learning_cards WHERE id = :id")
    suspend fun get(id: String): LearningCardEntity?

    @Query("SELECT * FROM learning_cards WHERE bookId = :bookId AND status = 'kept' ORDER BY chapterIndex ASC, sourceFraction ASC")
    fun observeKeptForBook(bookId: String): Flow<List<LearningCardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<LearningCardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: LearningCardEntity)

    @Query("UPDATE learning_cards SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAt: Long)

    @Query("DELETE FROM learning_cards WHERE id = :id")
    suspend fun delete(id: String)

    /** Drops a chapter's generated prompts so it can be generated again. */
    @Query(
        "DELETE FROM learning_cards " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND generatedByAi = 1"
    )
    suspend fun deleteGeneratedForChapter(bookId: String, chapterIndex: Int)

    @Query("DELETE FROM learning_cards WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

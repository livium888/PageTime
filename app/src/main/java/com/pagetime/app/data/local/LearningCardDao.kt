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

    /**
     * Every card the reader has not thrown away, newest book position first.
     *
     * Skipped rows are excluded here rather than filtered later: a discarded
     * prompt is not a card, and a screen for looking at your flashcards should
     * not make the reader scroll past the ones they rejected.
     */
    @Query(
        "SELECT * FROM learning_cards WHERE status != 'skipped' " +
            "ORDER BY bookId ASC, chapterIndex ASC, sourceFraction ASC"
    )
    fun observeLive(): Flow<List<LearningCardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<LearningCardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: LearningCardEntity)

    @Query("UPDATE learning_cards SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAt: Long)

    @Query("DELETE FROM learning_cards WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Clears a chapter's UNJUDGED prompts so it can be generated again.
     *
     * Kept cards are deliberately spared. A kept card is one the reader chose,
     * and it carries FSRS state — a difficulty, a stability, a review count
     * earned over weeks. Regenerating a chapter is a request for different
     * questions, not permission to delete the reader's own memory schedule,
     * and a "make new questions" button that silently destroyed review history
     * would be the worst kind of destructive: invisible until the day the card
     * failed to come back.
     *
     * Skipped rows go, because the reader asking for new questions has
     * withdrawn the rejection that kept them around.
     */
    @Query(
        "DELETE FROM learning_cards " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "AND generatedByAi = 1 AND status != 'kept'"
    )
    suspend fun deleteUnkeptForChapter(bookId: String, chapterIndex: Int)

    @Query("DELETE FROM learning_cards WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

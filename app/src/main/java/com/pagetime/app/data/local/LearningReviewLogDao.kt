package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The DAO learning_review_logs has been waiting for since the app was written.
 *
 * WHY AN APPEND-ONLY LOG AND NOT JUST THE CARD'S STATE
 *
 * Grading a card overwrites its FSRS state in place. That is correct for
 * scheduling and destroys everything else: the moment a card is answered, the
 * fact that it was answered is gone. The app could not say how many reviews
 * the reader has done, how many they got right, whether any of this works, or
 * what would happen under different scheduler parameters — not because the
 * data was expensive, but because nothing ever wrote it down.
 *
 * Orbit takes this much further: its entire store is an event log and every
 * card's state is derived by reducing it, which is why Orbit can replay
 * history under a changed scheduler. This is the cheap version of the same
 * idea — the state stays authoritative, and the log sits beside it as the
 * record of what actually happened.
 *
 * A row is written and never updated or deleted, except with its book.
 */
@Dao
interface LearningReviewLogDao {

    /** Returns the new row's id, so a mistaken answer can take its log with it. */
    @Insert
    suspend fun insert(log: LearningReviewLogEntity): Long

    @Query("DELETE FROM learning_review_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Recall across every review that was actually due.
     *
     * Early reviews are excluded deliberately. Answering a card an hour after
     * making it is not evidence of remembering anything, and counting those
     * would make the recall figure drift upward exactly as the reader does
     * more of the thing that does not help them.
     */
    @Query(
        "SELECT COUNT(*) AS reviews, " +
            "IFNULL(SUM(CASE WHEN rating > 1 THEN 1 ELSE 0 END), 0) AS remembered, " +
            "COUNT(DISTINCT cardId) AS cards " +
            "FROM learning_review_logs WHERE wasDue = 1"
    )
    fun observeTally(): Flow<ReviewTally>

    @Query(
        "SELECT COUNT(*) AS reviews, " +
            "IFNULL(SUM(CASE WHEN rating > 1 THEN 1 ELSE 0 END), 0) AS remembered, " +
            "COUNT(DISTINCT cardId) AS cards " +
            "FROM learning_review_logs WHERE wasDue = 1 AND bookId = :bookId"
    )
    fun observeTallyForBook(bookId: String): Flow<ReviewTally>

    /** Reviews in the last [sinceMillis], for "you have done N this week". */
    @Query("SELECT COUNT(*) FROM learning_review_logs WHERE reviewedAt >= :sinceMillis")
    fun observeCountSince(sinceMillis: Long): Flow<Int>

    @Query("SELECT * FROM learning_review_logs WHERE cardId = :cardId ORDER BY reviewedAt ASC")
    suspend fun forCard(cardId: String): List<LearningReviewLogEntity>

    @Query("DELETE FROM learning_review_logs WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

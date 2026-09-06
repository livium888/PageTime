package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CardEmbeddingDao {

    /** Every vector produced by [model]. Vectors from other models are useless here. */
    @Query("SELECT * FROM card_embeddings WHERE model = :model")
    suspend fun allForModel(model: String): List<CardEmbeddingEntity>

    @Query("SELECT * FROM card_embeddings WHERE cardId = :cardId AND model = :model")
    suspend fun forCard(cardId: String, model: String): CardEmbeddingEntity?

    /**
     * Cards this model has not embedded yet, oldest first — the backfill queue.
     *
     * The join is against the model as well as the card, so switching models
     * makes every card appear un-embedded again rather than leaving a box half
     * described in one space and half in another.
     */
    @Query(
        "SELECT c.* FROM lumen_cards c " +
            "LEFT JOIN card_embeddings e ON e.cardId = c.id AND e.model = :model " +
            "WHERE e.cardId IS NULL ORDER BY c.updatedAt ASC LIMIT :limit"
    )
    suspend fun cardsAwaitingEmbedding(model: String, limit: Int): List<LumenCardEntity>

    @Query("SELECT COUNT(*) FROM lumen_cards c " +
        "LEFT JOIN card_embeddings e ON e.cardId = c.id AND e.model = :model " +
        "WHERE e.cardId IS NULL")
    suspend fun countAwaitingEmbedding(model: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(embedding: CardEmbeddingEntity)

    /** Drops vectors from every model but [model] — what a model switch needs. */
    @Query("DELETE FROM card_embeddings WHERE model != :model")
    suspend fun deleteFromOtherModels(model: String)

    @Query("DELETE FROM card_embeddings")
    suspend fun clear()
}

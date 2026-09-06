package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One card's meaning as a vector, for finding notes that say the same thing in
 * different words.
 *
 * A SEPARATE TABLE, not a column on lumen_cards, for three reasons:
 *
 *  - A vector is roughly 1.5 KB. Listing the slip box would drag every one of
 *    them out of the database to render text, on every screen that shows cards.
 *  - It is derived data. Cards are what the reader wrote; these can be thrown
 *    away and rebuilt at any time, and a separate table makes "rebuild
 *    everything" one statement rather than a mass update of user rows.
 *  - Adding a ByteArray to the card entity would give that data class identity
 *    equality, silently changing how existing code compares cards.
 *
 * [model] is the point of the whole design. Vectors from two different models
 * live in different spaces and comparing across them is meaningless — not an
 * error, just quietly wrong answers, which is the failure this whole feature
 * has to keep designing against. Recording which model produced each vector
 * makes a model change something that can be detected and rebuilt, instead of
 * silently poisoning every comparison afterwards.
 *
 * Deleting a card takes its vector with it, by foreign key.
 */
@Entity(
    tableName = "card_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = LumenCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("model")],
)
class CardEmbeddingEntity(
    @PrimaryKey val cardId: String,
    /** Identifier of the model that produced [vector]. */
    val model: String,
    /** Length of [vector] in floats, so a mismatch is caught before comparing. */
    val dimensions: Int,
    /** The unit-length vector, big-endian floats. */
    val vector: ByteArray,
    val updatedAt: Long,
) {
    // Not a data class: a generated equals() would compare the ByteArray by
    // identity, so two rows holding the same vector would test unequal. Room
    // does not need data classes, and quietly wrong equality is exactly the
    // kind of bug this table exists to avoid elsewhere.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardEmbeddingEntity) return false
        return cardId == other.cardId &&
            model == other.model &&
            dimensions == other.dimensions &&
            updatedAt == other.updatedAt &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = cardId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

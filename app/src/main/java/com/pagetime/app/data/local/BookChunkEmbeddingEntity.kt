package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One piece of a book as a vector, so a reader can find a passage by what it
 * means rather than by remembering its words.
 *
 * WHY THE TEXT IS STORED HERE TOO
 *
 * A search result has to show the paragraph it found. Re-opening the EPUB,
 * unzipping it and re-parsing the chapter to display one paragraph would make
 * every result cost what a chapter load costs, for text the app has already
 * read once. A chunk is a few hundred characters against a 1,536-byte vector,
 * so keeping it roughly doubles a row and removes that entirely.
 *
 * The cost is honest and worth stating: a 300-page book is around 3,000 chunks
 * at roughly 2 KB each, so about 6 MB per indexed book. Indexes are derived
 * data and can be dropped and rebuilt whenever the reader wants the space
 * back.
 *
 * [startOffset] and [endOffset] index the chapter text the chunk was cut from,
 * which is what lets a result send the reader to the exact spot instead of to
 * the top of the chapter.
 *
 * [model] carries the same weight it does on card_embeddings: two embedding
 * models produce vectors in different spaces, and comparing across them
 * returns numbers rather than errors. Recording which model produced each row
 * makes a model change detectable and rebuildable instead of quietly wrong.
 *
 * Deleting a book takes its index with it, by foreign key.
 */
@Entity(
    tableName = "book_chunk_embeddings",
    primaryKeys = ["bookId", "chapterIndex", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["bookId", "model"])],
)
class BookChunkEmbeddingEntity(
    val bookId: String,
    val chapterIndex: Int,
    /** Position of this chunk within its chapter, from zero. */
    val ordinal: Int,
    /** Where the chunk begins in the chapter text, for jumping back to it. */
    val startOffset: Int,
    val endOffset: Int,
    /** The chunk itself, so a result can be shown without re-parsing the book. */
    val text: String,
    /** Identifier of the model that produced [vector]. */
    val model: String,
    /** Length of [vector] in floats, so a mismatch is caught before comparing. */
    val dimensions: Int,
    /** The unit-length vector, big-endian floats. */
    val vector: ByteArray,
    val indexedAt: Long,
) {
    // Not a data class, for the same reason card_embeddings is not: a generated
    // equals() compares the ByteArray by identity, so two rows holding the same
    // vector would test unequal. Quietly wrong equality is the exact class of
    // bug the model column exists to prevent elsewhere.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookChunkEmbeddingEntity) return false
        return bookId == other.bookId &&
            chapterIndex == other.chapterIndex &&
            ordinal == other.ordinal &&
            startOffset == other.startOffset &&
            endOffset == other.endOffset &&
            text == other.text &&
            model == other.model &&
            dimensions == other.dimensions &&
            indexedAt == other.indexedAt &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = bookId.hashCode()
        result = 31 * result + chapterIndex
        result = 31 * result + ordinal
        result = 31 * result + startOffset
        result = 31 * result + endOffset
        result = 31 * result + text.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + indexedAt.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

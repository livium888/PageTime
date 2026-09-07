package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookChunkEmbeddingDao {

    /**
     * Every chunk of one book, for searching it.
     *
     * Loaded whole rather than queried per comparison: a book is a few
     * thousand rows, and a cosine over 384 floats is a few hundred
     * multiplications, so the whole search is well under a second in memory
     * while a per-row query would pay SQLite's cost thousands of times.
     */
    @Query("SELECT * FROM book_chunk_embeddings WHERE bookId = :bookId AND model = :model")
    suspend fun forBook(bookId: String, model: String): List<BookChunkEmbeddingEntity>

    /** How much of a book is indexed, which is also how the UI knows it is done. */
    @Query(
        "SELECT COUNT(*) FROM book_chunk_embeddings WHERE bookId = :bookId AND model = :model"
    )
    suspend fun countForBook(bookId: String, model: String): Int

    /**
     * The highest chapter already indexed, so an interrupted index resumes
     * instead of starting the book again. Null when nothing is indexed yet.
     */
    @Query(
        "SELECT MAX(chapterIndex) FROM book_chunk_embeddings " +
            "WHERE bookId = :bookId AND model = :model"
    )
    suspend fun lastIndexedChapter(bookId: String, model: String): Int?

    /** Books with any chunks from this model, for showing what is searchable. */
    @Query("SELECT DISTINCT bookId FROM book_chunk_embeddings WHERE model = :model")
    suspend fun indexedBookIds(model: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<BookChunkEmbeddingEntity>)

    @Query("DELETE FROM book_chunk_embeddings WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /**
     * Drops everything a different model produced. A model change makes every
     * existing vector incomparable, and comparing across spaces returns
     * plausible numbers rather than an error, so the old rows have to go rather
     * than sit there being quietly wrong.
     */
    @Query("DELETE FROM book_chunk_embeddings WHERE model != :model")
    suspend fun deleteFromOtherModels(model: String)

    /** Reclaims the space; indexes are derived and can always be rebuilt. */
    @Query("DELETE FROM book_chunk_embeddings")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM book_chunk_embeddings")
    suspend fun countAll(): Int
}

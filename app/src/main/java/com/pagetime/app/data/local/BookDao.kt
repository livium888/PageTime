package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    /** Every book, once. For labelling cards with the book they came from. */
    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT * FROM books ORDER BY addedAt DESC LIMIT 1")
    suspend fun getMostRecent(): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE books SET currentChapterIndex = :chapter, scrollProgress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, chapter: Int, progress: Float)

    @Query("UPDATE books SET totalReadingSeconds = totalReadingSeconds + :seconds WHERE id = :id")
    suspend fun addReadingSeconds(id: String, seconds: Long)

    /** Books never classified by [com.pagetime.app.data.BookGenreClassifier] yet. */
    @Query("SELECT * FROM books WHERE genre IS NULL ORDER BY addedAt ASC LIMIT :limit")
    suspend fun getUnclassified(limit: Int): List<BookEntity>

    @Query("UPDATE books SET genre = :genre WHERE id = :id")
    suspend fun updateGenre(id: String, genre: String)

    /** See [com.pagetime.app.data.BookWordCounter] — computed once, cached forever after. */
    @Query("UPDATE books SET wordCount = :wordCount WHERE id = :id")
    suspend fun updateWordCount(id: String, wordCount: Int)
}

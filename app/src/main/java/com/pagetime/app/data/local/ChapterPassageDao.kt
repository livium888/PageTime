package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterPassageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(passages: List<ChapterPassageEntity>)

    @Query(
        "SELECT * FROM chapter_passages " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startOffset ASC"
    )
    suspend fun forChapter(bookId: String, chapterIndex: Int): List<ChapterPassageEntity>

    @Query(
        "SELECT * FROM chapter_passages " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startOffset ASC"
    )
    fun observeForChapter(bookId: String, chapterIndex: Int): Flow<List<ChapterPassageEntity>>

    /**
     * Clears a chapter's record before a fresh one is written.
     *
     * Upserting by ordinal is not enough. Ordinals identify a chunk within one
     * INDEX, and re-indexing renumbers them — so rows describing the old
     * chunks survived beside the new ones and the sheet showed both. It read
     * as 45 passages from a chapter that can send at most 24, with duplicate
     * rows and stale mid-sentence text among them.
     */
    @Query(
        "DELETE FROM chapter_passages " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex"
    )
    suspend fun deleteForChapter(bookId: String, chapterIndex: Int)

    @Query("DELETE FROM chapter_passages WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

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

    @Query("DELETE FROM chapter_passages WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    /** "epub" or "txt" */
    val format: String,
    val localPath: String,
    val coverUrl: String?,
    val addedAt: Long,
    val currentChapterIndex: Int = 0,
    val scrollProgress: Float = 0f,
    val totalReadingSeconds: Long = 0
)

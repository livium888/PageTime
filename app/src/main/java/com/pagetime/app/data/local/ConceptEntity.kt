package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A book-scoped concept discovered while the reader progresses through a book. */
@Entity(
    tableName = "concepts",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "normalizedLabel"], unique = true)
    ]
)
data class ConceptEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val label: String,
    val normalizedLabel: String,
    val description: String,
    /** idea, person, place, process, event, principle, or other. */
    val type: String,
    val firstChapterIndex: Int,
    val lastChapterIndex: Int,
    val sourceQuote: String?,
    val confidence: Float,
    val mentionCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index

/** Prevents duplicate Gemini calls for the same bounded reading window. */
@Entity(
    tableName = "learning_generations",
    primaryKeys = ["bookId", "generationKey"],
    indices = [Index(value = ["bookId", "chapterIndex"])]
)
data class LearningGenerationEntity(
    val bookId: String,
    val generationKey: String,
    val chapterIndex: Int,
    val status: String,
    val cardCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

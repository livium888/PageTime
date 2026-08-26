package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Auditable metadata for one Gemini content-analysis request. */
@Entity(
    tableName = "ai_usage_events",
    indices = [
        androidx.room.Index(value = ["createdAt"]),
        androidx.room.Index(value = ["bookId"])
    ]
)
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** "cards" or "concepts"; never contains the book text. */
    val operation: String,
    val model: String,
    val status: String,
    val inputCharacters: Int,
    val outputItems: Int = 0,
    val secondaryItems: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null
)

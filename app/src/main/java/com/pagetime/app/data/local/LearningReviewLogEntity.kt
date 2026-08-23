package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Immutable audit record for every intentional comprehension review. */
@Entity(tableName = "learning_review_logs")
data class LearningReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val bookId: String,
    val reviewedAt: Long,
    val rating: Int,
    val scheduledDays: Long,
    val elapsedDays: Long,
    val wasDue: Boolean
)

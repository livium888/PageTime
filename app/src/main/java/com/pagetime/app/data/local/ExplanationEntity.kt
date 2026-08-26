package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores a reader's explanation of a concept and the AI's evaluation.
 * This replaces MCQ cards with genuine understanding verification — the
 * Feynman Technique in database form.
 */
@Entity(
    tableName = "explanations",
    indices = [Index(value = ["bookId", "chapterIndex"])]
)
data class ExplanationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val conceptLabel: String,
    val conceptKeyPoints: String,       // JSON array of key points the reader should cover
    val userExplanation: String,
    val aiFeedback: String?,            // full AI evaluation text
    val accuracyScore: Int?,            // 1–5
    val completenessScore: Int?,        // 1–5
    val clarityScore: Int?,             // 1–5
    val overallScore: Float?,           // average of the three
    val whatTheyGotRight: String?,
    val whatTheyMissed: String?,
    val suggestedImprovement: String?,
    val simplerVersion: String?,        // AI's simpler version of the explanation
    val createdAt: Long,
    val updatedAt: Long
)

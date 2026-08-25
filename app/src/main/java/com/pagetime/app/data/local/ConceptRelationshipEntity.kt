package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A directed, typed edge in a book's concept graph. */
@Entity(
    tableName = "concept_relationships",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "sourceConceptId", "targetConceptId", "relationType"], unique = true)
    ]
)
data class ConceptRelationshipEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val sourceConceptId: String,
    val targetConceptId: String,
    /** causes, supports, contrasts with, depends on, example of, defines, etc. */
    val relationType: String,
    val explanation: String,
    val sourceQuote: String?,
    val confidence: Float,
    val firstChapterIndex: Int,
    val createdAt: Long,
    val updatedAt: Long
)

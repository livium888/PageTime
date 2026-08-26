package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A comprehension prompt linked to a precise book location or supporting source quote. */
@Entity(
    tableName = "learning_cards",
    indices = [Index(value = ["bookId", "generationKey"])]
)
data class LearningCardEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val topic: String? = null,
    val prompt: String,
    val answer: String,
    val explanation: String?,
    /** Readium Locator JSON or a plain-text fraction encoded as text. */
    val sourceLocator: String?,
    val sourceFraction: Float?,
    /** Exact normalized source text returned by Gemini and validated locally. */
    val sourceQuote: String? = null,
    /** FSRS Card JSON. Kept opaque so the library owns its state format. */
    val fsrsCardJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRating: Int? = null,
    val reviewCount: Int = 0,
    val generatedByAi: Boolean = false,
    val aiConfidence: Float? = null,
    /** Hash of the bounded context window; prevents regenerating the same chapter. */
    val generationKey: String? = null,
    /**
     * Card type per Wozniak's 20 rules of knowledge formulation.
     * "qa"   = standard question-and-answer (default, backward-compatible)
     * "cloze" = cloze deletion — prompt contains the sentence with {{c1::answer}}
     * "mcq"  = multiple choice — mcqOptions holds the JSON array of choices
     */
    val cardType: String = "qa",
    /** JSON array of 3–4 answer choices for MCQ cards, e.g. `["A","B","C","D"]`. */
    val mcqOptions: String? = null
)

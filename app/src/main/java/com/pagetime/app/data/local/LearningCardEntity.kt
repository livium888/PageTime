package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A comprehension prompt linked to a precise book location or supporting source quote. */
@Entity(
    tableName = "learning_cards",
    indices = [
        Index(value = ["bookId", "generationKey"]),
        Index(value = ["status", "dueAt"])
    ]
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
    val mcqOptions: String? = null,
    /**
     * Whether the reader has accepted this prompt.
     *
     * A generated prompt is not a card until a person says so. It is written
     * down before that — generating a chapter costs an API call, and losing
     * the batch because the reader closed the book would mean paying for it
     * twice — but a PENDING row is never scheduled and never reviewed.
     *
     * [STATUS_PENDING] offered but not yet judged, [STATUS_KEPT] accepted,
     * [STATUS_SKIPPED] rejected and never to be offered again.
     */
    val status: String = STATUS_KEPT,
    /**
     * Next scheduled review (epoch ms); null until the card is kept.
     *
     * Duplicated out of [fsrsCardJson] because a due query has to be a WHERE
     * clause. The scheduler owns the JSON; this column exists so SQLite can
     * answer "what is due" without parsing every card in the table.
     */
    val dueAt: Long? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_KEPT = "kept"
        const val STATUS_SKIPPED = "skipped"
    }
}

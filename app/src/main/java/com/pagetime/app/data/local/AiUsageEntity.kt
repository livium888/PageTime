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
    /**
     * Characters sent.
     *
     * Kept for the on-device model, which reports no token counts, and for
     * rows written before token capture existed. It is NOT a token count and
     * must never be presented as one: dividing it by a constant was how this
     * app used to guess, and the guess was wrong in both directions.
     */
    val inputCharacters: Int,
    val outputItems: Int = 0,
    val secondaryItems: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    /**
     * Tokens as the API reported them; null when it reported none.
     *
     * Null and zero mean different things here. Null is "not measured" — the
     * on-device model, a failed request, a response with no usage block. Zero
     * would be "measured, and it was free", which is a claim this app should
     * never make on the reader's behalf.
     */
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    /** Reasoning on a thinking model. Billed as output. */
    val thinkingTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** As reported by the API. Never recomputed from the parts. */
    val totalTokens: Int? = null
)

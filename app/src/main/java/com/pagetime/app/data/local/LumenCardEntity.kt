package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Lumen card: a manually captured index note created at the reader's current
 * position (Options → New Lumen card). The card lives in a numbered slip-box
 * [box] at a stable Luhmann-style address [indexNumber] (e.g. "21/2a7"):
 *
 * - [front]/[back] are always editable by the user (AI only drafts them).
 * - [snippetsJson] is append-only: "Add context" adds a dated snippet instead
 *   of rewriting history, so the card becomes a trail of re-encounters.
 * - [keywords] enable free, local re-encounter detection while reading.
 * - [linksJson] holds explicit cross-references to other Lumen cards, so the
 *   slip box becomes a navigable web of notes rather than a flat list.
 * - [fsrsCardJson]/[dueAt]/[reviewCount]/[lastRating] power optional spaced
 *   training of the slip box, reusing the same FSRS scheduler as learning cards.
 */
@Entity(
    tableName = "lumen_cards",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["updatedAt"]),
        Index(value = ["box"]),
        Index(value = ["dueAt"])
    ]
)
data class LumenCardEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** Which slip box the card is filed in (1-based). */
    val box: Int = 1,
    /** Stable Luhmann-style address within the box, e.g. "21/2a7". */
    val indexNumber: String = "",
    /** Title/question line of the card. */
    val front: String,
    /** Body of the card: AI one-liner draft or the user's own words. */
    val back: String,
    /** The passage text the card was captured from. */
    val quote: String,
    /** Readium locator JSON for EPUBs; used to jump back to the source page. */
    val sourceLocatorJson: String?,
    val sourceChapterIndex: Int?,
    /** 0..1 position in a plain-text book, for jumping back to the source. */
    val sourceFraction: Float,
    /** Append-only evolution history, oldest first: [{text, addedAt, fraction}]. */
    val snippetsJson: String = "[]",
    /** IDs of other Lumen cards this card references, JSON array of strings. */
    val linksJson: String = "[]",
    /** Space-separated significant words for local re-encounter matching. */
    val keywords: String = "",
    /** FSRS state for optional training; null until the card is first trained. */
    val fsrsCardJson: String? = null,
    /** Next scheduled training time (epoch ms); null = not in training yet. */
    val dueAt: Long? = null,
    val reviewCount: Int = 0,
    val lastRating: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)

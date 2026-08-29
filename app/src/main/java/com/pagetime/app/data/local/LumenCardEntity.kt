package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Lumen card: a manually captured index note created at the reader's current
 * position (Options → New Lumen card). Unlike scheduled learning cards, Lumen
 * cards are Luhmann-style notes whose value grows over time:
 *
 * - [front]/[back] are always editable by the user (AI only drafts them).
 * - [snippetsJson] is append-only: "Add context" adds a dated snippet instead
 *   of rewriting history, so the card becomes a trail of re-encounters.
 * - [keywords] enable free, local re-encounter detection while reading.
 */
@Entity(
    tableName = "lumen_cards",
    indices = [Index(value = ["bookId"]), Index(value = ["updatedAt"])]
)
data class LumenCardEntity(
    @PrimaryKey val id: String,
    val bookId: String,
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
    /** Space-separated significant words for local re-encounter matching. */
    val keywords: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

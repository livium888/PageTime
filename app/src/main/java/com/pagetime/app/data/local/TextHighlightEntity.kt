package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persistent highlight: a span of a book the reader marked.
 *
 * Two anchor models, one per book format:
 *
 * - Plain text: whole-page spans, [startOffset]..[endOffset] as character
 *   offsets in the book text. Page boundaries are exact ([TextPage] carries
 *   them), so a highlight begun on page 3 and ended on page 5 covers pages 3,
 *   4 and 5 without any selection machinery in a paged reader.
 * - EPUB: the native Readium selection. [startLocatorJson] is the selection's
 *   Locator, whose offsets already span the selected range — in a paginated
 *   reader that range can cover several rendered pages inside one resource.
 *   [endLocatorJson] is reserved for cross-resource spans (start captured in
 *   one chapter, end in another); v1 stores a single locator.
 *
 * [quote] is the highlighted text itself, so the highlight can be shown and
 * turned into a card without re-opening the book.
 *
 * Deleting a book deletes its highlights: a span with nothing to open is a
 * ghost mark.
 */
@Entity(
    tableName = "text_highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"])]
)
data class TextHighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** "txt" or "epub". */
    val kind: String,
    /** txt: start char offset in the whole book text. -1 when unused. */
    val startOffset: Int = -1,
    /** txt: exclusive end char offset. -1 when unused. */
    val endOffset: Int = -1,
    /** epub: the selection Locator JSON; null for txt books. */
    val startLocatorJson: String? = null,
    /** epub: end Locator JSON for a cross-resource span; null in v1. */
    val endLocatorJson: String? = null,
    /** The highlighted text, capped; for capture and display. */
    val quote: String,
    val createdAt: Long
)
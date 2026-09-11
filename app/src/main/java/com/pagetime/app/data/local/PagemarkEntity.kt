package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chunk of a book being read incrementally — Polar's "pagemark".
 *
 * A pagemark is a span of reading: where it starts, where it currently ends,
 * and what state it is in. EPUB books are anchored by Readium [Locator] JSON
 * (the same format LumenCardEntity.sourceLocatorJson already uses); plain-text
 * books use a 0..1 fraction of the whole text.
 *
 * States (see PagemarkSession.State):
 *
 * - QUEUED      — added to the reading queue, not started.
 * - READING     — the chunk the reader is currently working through. At most
 *                 one per book.
 * - SUSPENDED   — started and paused before finishing. Returns to the queue
 *                 by priority; starting it again makes it READING.
 * - DONE        — closed with a rating. [dueAt] (driven by [fsrsCardJson],
 *                 the same FSRS scheduler as learning cards) is when the chunk
 *                 is worth re-reading; when it is past due it returns to the
 *                 front of the queue.
 *
 * Deleting a book deletes its pagemarks: the spans live inside the book, so
 * a chunk with nothing to open is just a ghost title.
 */
@Entity(
    tableName = "pagemarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["dueAt"])
    ]
)
data class PagemarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** Reader-chosen or auto-generated label, e.g. "Ch. 4 — the opium economy". */
    val title: String,
    /** Readium Locator JSON for EPUBs; null for plain-text books. */
    val startLocatorJson: String?,
    /** 0..1 position in a plain-text book; the fallback anchor for EPUBs. */
    val startFraction: Float,
    /** Where the chunk currently ends; null until the chunk is closed. */
    val endLocatorJson: String?,
    /** 0..1 position where the chunk currently ends. */
    val endFraction: Float,
    /** PagemarkSession.State name: QUEUED, READING, SUSPENDED or DONE. */
    val state: String,
    /** 1..5; higher chunks surface sooner in the queue. */
    val priority: Int,
    /** FSRS state for re-reading; null until the chunk is first closed. */
    val fsrsCardJson: String? = null,
    /** Next re-reading time (epoch ms); null = not scheduled. */
    val dueAt: Long? = null,
    val reviewCount: Int = 0,
    val lastRating: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)
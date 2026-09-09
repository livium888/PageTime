package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * A book on a shelf, which does NOT have to be a book the reader has.
 *
 * THE POINT OF THIS TABLE
 *
 * Every other book in the app lives in `books`, and every row there has a
 * localPath: it is downloaded and on disk. That made a reading list impossible
 * to express — a hundred-book path would have meant a hundred downloads before
 * the reader had chosen anything.
 *
 * So a shelf entry carries only what a list needs to say: a title, an author,
 * and where it sits in the order. Everything else is optional and gets filled
 * in as the app learns it — which catalogue has it, whether it has been
 * fetched, and the id of the downloaded book once there is one.
 *
 * WHY THE ORDER IS A COLUMN
 *
 * A ladder read alphabetically is not a ladder. Position is stored rather than
 * derived so a reader can eventually reorder their own shelves without the
 * meaning of a curated one drifting.
 *
 * WHY availability IS A CACHE, NOT A FACT
 *
 * It records what a catalogue said when we last asked, and catalogues go down.
 * It is never consulted for a book the reader already owns — see
 * ShelfRows.stateOf, where owning always wins — so a dead feed cannot grey out
 * something sitting on the device.
 */
@Entity(
    tableName = "shelf_books",
    primaryKeys = ["shelfId", "slotId"],
    indices = [Index(value = ["shelfId", "position"])],
)
data class ShelfBookEntity(
    /** Which shelf. Curated lists use a constant id; system shelves likewise. */
    val shelfId: String,

    /** Stable within the shelf. For a curated list this is the ladder's own key. */
    val slotId: String,

    val title: String,
    val author: String,

    /** Where it sits in the reading order. */
    val position: Int,

    /** One line on why it is here. Null for shelves the reader made. */
    val note: String? = null,

    /**
     * Which catalogue answered, and with what.
     *
     * [catalogBookId] is also the id a downloaded book gets, so owning is
     * checked by looking for that id in `books` — no title matching, which
     * would confidently pair the wrong translation.
     */
    val catalogSource: String? = null,
    val catalogBookId: String? = null,

    /** "unknown", "available" or "unavailable" — see ShelfAvailability. */
    val availability: String = "unknown",

    /** When a catalogue was last asked. Null means never. */
    val resolvedAt: Long? = null,

    val addedAt: Long = 0,
)

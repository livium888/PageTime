package com.pagetime.app.data.shelf

/**
 * What a shelf row can say about a book, and what it must never say.
 *
 * NEVER A CLAIM ABOUT COPYRIGHT
 *
 * The obvious way to build a canon shelf is to mark each entry public domain
 * or not. It would be wrong in a way that is hard to notice: copyright depends
 * on where the reader is standing, changes every January, and differs between
 * a work, a translation and an edition. An app that tells someone a book is
 * free when it is not has told them something worse than nothing.
 *
 * So this never asks that question. It asks a much smaller one — did the
 * catalogues we serve answer with this book — and reports exactly that. A book
 * we cannot fetch is described as one WE cannot fetch, with the suggestion
 * that it be found elsewhere. That is both true everywhere and useful.
 *
 * WHY UNAVAILABLE ENTRIES STAY ON THE SHELF
 *
 * Quietly dropping them would be tidier and would misrepresent the thing the
 * shelf exists to show. A reading path with the unobtainable books removed is
 * not a shorter path, it is a different and misleading one — and the reader
 * who wants Ulysses can get it from a shop in an afternoon. The list is also
 * an education; it should say what is worth reading, not merely what is
 * convenient to serve.
 */
enum class ShelfAvailability {
    /** Not asked yet. */
    UNKNOWN,

    /** A catalogue has it, and it can be downloaded now. */
    AVAILABLE,

    /** Asked, and no catalogue we serve had it. */
    UNAVAILABLE;

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String?): ShelfAvailability =
            entries.firstOrNull { it.key == key?.lowercase() } ?: UNKNOWN
    }
}

/** What a row on a shelf is doing right now, from the reader's point of view. */
sealed interface ShelfRowState {

    /** Already downloaded. [progress] is 0..1 through the book. */
    data class Owned(val bookId: String, val progress: Float) : ShelfRowState

    /** In a catalogue and one tap away. */
    data class Downloadable(val source: String, val catalogBookId: String) : ShelfRowState

    /** Not in any catalogue we serve. */
    data object SourceElsewhere : ShelfRowState

    /** We have not looked yet. */
    data object Checking : ShelfRowState
}

object ShelfRows {

    /**
     * Owned beats everything.
     *
     * A book the reader has on disk is available whatever a catalogue said
     * last week — catalogues go down, feeds get reshaped, and a resolve that
     * failed this morning must never grey out a book that is sitting in the
     * library. This ordering is the whole reason the check is a function
     * rather than a stored flag.
     */
    fun stateOf(
        ownedBookId: String?,
        ownedProgress: Float,
        availability: ShelfAvailability,
        catalogSource: String?,
        catalogBookId: String?,
    ): ShelfRowState = when {
        ownedBookId != null -> ShelfRowState.Owned(ownedBookId, ownedProgress.coerceIn(0f, 1f))
        availability == ShelfAvailability.AVAILABLE &&
            !catalogSource.isNullOrBlank() &&
            !catalogBookId.isNullOrBlank() ->
            ShelfRowState.Downloadable(catalogSource, catalogBookId)
        // Claimed available but with nothing to fetch is a resolve that half
        // finished. Treat it as unchecked rather than offering a button that
        // cannot work.
        availability == ShelfAvailability.AVAILABLE -> ShelfRowState.Checking
        availability == ShelfAvailability.UNAVAILABLE -> ShelfRowState.SourceElsewhere
        else -> ShelfRowState.Checking
    }

    /** The line under an unavailable title. Never mentions copyright. */
    fun sourceElsewhereNote(): String =
        "Not in the libraries PageTime can reach — you'll need to find this one yourself."

    /** Whether this row should be dimmed. */
    fun isDimmed(state: ShelfRowState): Boolean = state is ShelfRowState.SourceElsewhere

    /**
     * How far down the ladder the reader has actually got.
     *
     * Counts only books they own and have finished, and — deliberately —
     * counts entries we cannot supply as not done rather than skipping them.
     * A progress figure that quietly excluded the hard-to-get books would
     * flatter the reader by redefining the goal.
     */
    fun completed(states: List<ShelfRowState>, finishedAt: Float = 0.95f): Int =
        states.count { it is ShelfRowState.Owned && it.progress >= finishedAt }
}

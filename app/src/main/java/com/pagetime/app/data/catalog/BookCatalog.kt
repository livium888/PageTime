package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.BookPage

/**
 * One place the reader can get books from.
 *
 * Sources used to be an enum with a `when` over it in the view model, the
 * search-everything path and twice in the screen — so adding one meant finding
 * four places and a catalogue that only answers searches had that fact spelled
 * out as `error("Search Internet Archive by title or author")` inside a branch.
 * A catalogue now carries what makes it different, and the places that used to
 * switch on it iterate a list instead.
 */
interface BookCatalog {

    /** Stable key. Persisted and used to match a chip, so it never changes. */
    val id: String

    /** What the chip says. */
    val label: String

    /**
     * What kind of books this holds, in a few words. Shown when the catalogue
     * comes back with nothing, where "no results" alone tells the reader
     * neither what they searched nor whether it was the right place to look.
     */
    val note: String

    /**
     * False when the catalogue has no browse mode and only answers a query.
     * Internet Archive is the case: it has no "show me anything" endpoint.
     */
    val browsable: Boolean get() = true

    /** A page of whatever the catalogue leads with. Only called when [browsable]. */
    suspend fun browse(page: Int): BookPage

    suspend fun search(query: String, page: Int): BookPage
}

/**
 * What the last load actually did.
 *
 * An empty shelf is not one state but three, and the app used to draw all of
 * them the same way: a source that answered with no matches, a source that
 * needs a query first, and a source that did not answer at all rendered as the
 * same blank list. The third is the one that matters — it is the whole reason a
 * catalogue whose feed has quietly died looks exactly like a catalogue that
 * simply has no Dickens.
 */
sealed interface CatalogHealth {

    /** Books are on screen. */
    data object Working : CatalogHealth

    /** The catalogue answered, and had nothing matching. */
    data class NothingMatched(val label: String, val query: String, val note: String) : CatalogHealth

    /** The catalogue only answers searches, and there is no query yet. */
    data class NeedsQuery(val label: String, val note: String) : CatalogHealth

    /** The catalogue did not answer. Never to be confused with the two above. */
    data class Unreachable(val label: String, val detail: String) : CatalogHealth

    /**
     * A search across every catalogue, where some did not answer. The result is
     * still usable, so this is a caveat on it rather than a failure.
     */
    data class PartlyReachable(val silent: List<String>) : CatalogHealth
}

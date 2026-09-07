package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.embed.BookSearchHit

/**
 * Everything the search sheet needs to know, in one value.
 *
 * A sealed hierarchy was the obvious shape and the wrong one: a half-indexed
 * book is searchable AND still indexing AND showing results, and states that
 * overlap are not states. So these are the facts, and the sheet decides what
 * they add up to.
 */
data class BookSearchState(
    /** Without the retrieval model there is nothing to index and nothing to ask. */
    val modelInstalled: Boolean = false,
    val chaptersTotal: Int = 0,
    val chaptersIndexed: Int = 0,
    val indexing: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<BookSearchHit> = emptyList(),
    /**
     * Whether [results] is an answer or just an empty starting point.
     *
     * "No results" and "you have not asked anything" look identical in a list
     * and mean opposite things, and only one of them should say the book has
     * nothing on the subject.
     */
    val answered: Boolean = false,
) {
    val anythingIndexed: Boolean get() = chaptersIndexed > 0

    val complete: Boolean get() = chaptersTotal > 0 && chaptersIndexed >= chaptersTotal

    /** Searchable in part is still searchable — the reader is told how much. */
    val searchable: Boolean get() = modelInstalled && anythingIndexed

    companion object {
        /**
         * Roughly what an indexed book costs on disk.
         *
         * A chunk is about 380 characters of text beside a 1,536-byte vector,
         * and chunks overlap, so a book's index runs to several megabytes.
         * Quoted as a typical figure rather than computed per book: knowing the
         * real number means reading the whole book first, which is the work the
         * reader is being asked to authorise.
         */
        const val TYPICAL_SIZE = "about 6 MB for a 300-page book"
    }
}

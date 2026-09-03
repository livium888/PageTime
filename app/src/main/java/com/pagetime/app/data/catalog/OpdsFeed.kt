package com.pagetime.app.data.catalog

/**
 * Everything that differs between one OPDS catalogue and the next.
 *
 * OPDS is Atom with agreed link relations for acquiring a book, so a catalogue
 * that speaks it needs a URL and a few preferences rather than a client of its
 * own. Standard Ebooks was 216 lines of hand-written Atom parsing; as a
 * descriptor it is the block below.
 *
 * This is what makes adding sources tractable: the parser is a standard, so it
 * is testable offline against saved feeds, and the only genuinely unknown thing
 * about a new source is its URL — which, when wrong, now announces itself
 * through CatalogHealth instead of rendering as an empty shelf.
 */
data class OpdsFeed(

    /** Stable key, also written to each book's `source` field. */
    val id: String,

    val label: String,

    val note: String,

    /** Base feed URL, without query parameters. */
    val url: String,

    /**
     * Query that stands in for "show me anything".
     *
     * Some feeds only answer a search. Standard Ebooks redirects to an HTML
     * page without a query, so browsing asks for "the", which most of the
     * catalogue matches.
     */
    val browseQuery: String? = null,

    val pageSize: Int = 30,

    /**
     * Added to a hash of the entry id to make a Long the app can use as a
     * primary key. Each catalogue needs its own range or two sources collide —
     * Gutenberg sits under 10M, Open Library above 20M, Standard Ebooks at 30M.
     */
    val idOffset: Long,

    /**
     * Removed from an entry id before hashing.
     *
     * Ids are persisted, and the download list is matched by id — so changing
     * how one is derived makes every book the reader already has look
     * undownloaded. Standard Ebooks hashed the path rather than the whole URL,
     * and this keeps that true through the move to the shared parser.
     */
    val idPrefix: String? = null,

    /**
     * Take the book's id from the digits at the end of the entry id rather than
     * from a hash of it.
     *
     * Project Gutenberg needs this. Its books have real numbers — Pride and
     * Prejudice is 1342 — and the app already stores them that way from the
     * gutendex route. Hashing the entry URL instead would give the same book a
     * different id depending on which route fetched it, so a book already on
     * the reader's shelf would show as undownloaded whenever the fallback
     * served it.
     */
    val numericEntryIds: Boolean = false,

    /** Keep only entries in this language; null keeps all of them. */
    val language: String? = "en",

    /**
     * How this feed names its query and paging parameters.
     *
     * OPDS standardises the feed, not the URL that asks for it. Standard Ebooks
     * takes per-page and page; Project Gutenberg takes a start_index counted in
     * items and no page size at all. Assuming one house's spelling was the
     * quiet reason a second feed could only ever return its first page.
     */
    val queryParam: String = "query",
    val pageParam: String = "page",
    val pageSizeParam: String? = "per-page",

    /** True when [pageParam] carries an item offset rather than a page number. */
    val pageIsOffset: Boolean = false,

    /**
     * Ranking hints for a feed offering several EPUBs of the same book.
     * Standard Ebooks publishes a "compatible" edition that works on every
     * reader and an "advanced" one that does not; the first hint that matches a
     * link's title wins.
     */
    val preferEditions: List<String> = emptyList(),
)

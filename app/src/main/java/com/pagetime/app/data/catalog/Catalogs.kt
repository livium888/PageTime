package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.GutenbergApi

/**
 * The catalogues the app ships with, in the order the chips appear.
 *
 * Both are human-made, and that is now the entry requirement. Standard Ebooks
 * hand-typesets its editions against a style manual; Project Gutenberg's texts
 * are transcribed and proofread by people before an EPUB is built from a clean
 * master. Somebody read every word in both.
 *
 * Open Library, the Internet Archive and Wikisource were here and are not any
 * more. The first two serve EPUBs derived from photographs of paper by OCR, so
 * what arrives carries running heads and page numbers mid-sentence, words still
 * broken across the original line, stray footnote digits, and chapters that
 * begin wherever a scan happened to start. No work on this side improves that:
 * the damage is in the file before the app ever sees it. Wikisource is the sad
 * one — a validated work there is excellent, but its quality is per-page and
 * cannot be told apart from a raw OCR dump through the search API, so listing
 * it was offering a coin flip.
 *
 * A catalogue is worth having when its worst book is still readable.
 */
class BookCatalogs(
    gutenberg: GutenbergApi,
    standardEbooks: BookCatalog = OpdsCatalog(STANDARD_EBOOKS),
) {
    val all: List<BookCatalog> = listOf(
        standardEbooks,
        // Gutendex first, its own site second. Gutendex is a third-party mirror
        // and had been the only way in, so one host being down took seventy
        // thousand books out of the app while gutenberg.org went on serving
        // them.
        FallbackCatalog(
            preferred = object : BookCatalog {
                override val id = "gutenberg"
                override val label = "Gutenberg"
                override val note = "Seventy thousand public-domain books."
                override suspend fun browse(page: Int) = gutenberg.browse(page)
                override suspend fun search(query: String, page: Int) = gutenberg.search(query, page)
            },
            backup = OpdsCatalog(GUTENBERG_OPDS),
        ),
    )

    val default: BookCatalog get() = all.first()

    fun byId(id: String?): BookCatalog = all.firstOrNull { it.id == id } ?: default

    /**
     * What to call the source a book came from.
     *
     * Read from the registry rather than a `when` in the screen. That `when`
     * listed three ids and sent everything else to an else-branch that said
     * "Project Gutenberg", so the first catalogue added after it was written
     * had its books attributed to the wrong library — silently, and in the one
     * place the reader looks to know where a book is from.
     */
    fun labelForSource(id: String?): String =
        all.firstOrNull { it.id == id }?.label ?: id?.takeIf { it.isNotBlank() } ?: "Unknown source"

    companion object {
        /**
         * Standard Ebooks, the first catalogue moved onto the shared OPDS
         * client — chosen deliberately, because it is a source known to work,
         * so the parser is proved against a real feed before anything new is
         * trusted to it.
         *
         * The feed needs a query: without one it redirects to an HTML page, so
         * browsing asks for "the", which most of the catalogue matches. The
         * edition hints matter — Standard Ebooks publishes an "advanced" EPUB
         * that not every reader can open alongside the recommended compatible
         * one. And the id prefix is load-bearing: ids are persisted and the
         * download list is matched by them, so hashing the whole URL rather
         * than the path would make every book the reader already has look
         * undownloaded.
         */
        val STANDARD_EBOOKS = OpdsFeed(
            id = "standardebooks",
            label = "Standard Ebooks",
            note = "Hand-made editions of public-domain classics.",
            url = "https://standardebooks.org/feeds/atom/all",
            browseQuery = "the",
            idOffset = 30_000_000L,
            idPrefix = "https://standardebooks.org/ebooks/",
            preferEditions = listOf("compatible", "Recommended"),
        )

        /**
         * Project Gutenberg's own OPDS feed, used when gutendex.com cannot be
         * reached. It pages by an item offset rather than a page number, which
         * is why OpdsFeed had to stop assuming Standard Ebooks' spelling of
         * those parameters.
         *
         * Its ids are the real Gutenberg numbers, so a book fetched this way is
         * the same book the app already had.
         */
        val GUTENBERG_OPDS = OpdsFeed(
            id = "gutenberg",
            label = "Gutenberg",
            note = "Seventy thousand public-domain books.",
            url = "https://www.gutenberg.org/ebooks/search.opds/",
            browseQuery = "the",
            pageSize = 25,
            idOffset = 0L,
            numericEntryIds = true,
            queryParam = "query",
            pageParam = "start_index",
            pageSizeParam = null,
            pageIsOffset = true,
        )
    }
}

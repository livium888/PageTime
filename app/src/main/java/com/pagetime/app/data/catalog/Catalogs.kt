package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.internetarchive.InternetArchiveApi
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import com.pagetime.app.data.wikisource.WikisourceApi

/**
 * The catalogues the app ships with, in the order the chips appear.
 *
 * Standard Ebooks leads because it is the most reliable: dedicated servers,
 * hand-made EPUBs, rarely rate-limited, so a first run always works.
 */
class BookCatalogs(
    gutenberg: GutenbergApi,
    openLibrary: OpenLibraryApi,
    internetArchive: InternetArchiveApi,
    wikisource: WikisourceApi = WikisourceApi(),
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
        object : BookCatalog {
            override val id = "openlibrary"
            override val label = "Open Library"
            override val note = "The Internet Archive's catalogue of everything."
            override suspend fun browse(page: Int) = openLibrary.browse(page = page)
            override suspend fun search(query: String, page: Int) = openLibrary.search(query, page)
        },
        object : BookCatalog {
            override val id = "internetarchive"
            override val label = "Internet Archive"
            override val note = "Scanned books, findable by title or author."
            // No browse endpoint: the archive has no "show me anything" mode.
            override val browsable = false
            override suspend fun browse(page: Int): BookPage =
                error("Internet Archive has no browse mode")
            override suspend fun search(query: String, page: Int) =
                internetArchive.search(query, page)
        },
        object : BookCatalog {
            override val id = "wikisource"
            override val label = "Wikisource"
            override val note = "Hand-transcribed texts, many outside English."
            // Wikisource has no most-read or newest to lead with, and a broad
            // search dressed as a front page would be an arbitrary slice.
            override val browsable = false
            override suspend fun browse(page: Int): BookPage =
                error("Wikisource has no browse mode")
            override suspend fun search(query: String, page: Int) =
                wikisource.search(query, page)
        },
    )

    val default: BookCatalog get() = all.first()

    fun byId(id: String?): BookCatalog = all.firstOrNull { it.id == id } ?: default

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

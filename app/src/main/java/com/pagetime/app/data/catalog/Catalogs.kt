package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.internetarchive.InternetArchiveApi
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import com.pagetime.app.data.standardebooks.StandardEbooksApi

/**
 * The catalogues the app ships with, in the order the chips appear.
 *
 * Standard Ebooks leads because it is the most reliable: dedicated servers,
 * hand-made EPUBs, rarely rate-limited, so a first run always works.
 */
class BookCatalogs(
    standardEbooks: StandardEbooksApi,
    gutenberg: GutenbergApi,
    openLibrary: OpenLibraryApi,
    internetArchive: InternetArchiveApi,
) {
    val all: List<BookCatalog> = listOf(
        object : BookCatalog {
            override val id = "standardebooks"
            override val label = "Standard Ebooks"
            override val note = "Hand-made editions of public-domain classics."
            override suspend fun browse(page: Int) = standardEbooks.browse(page)
            override suspend fun search(query: String, page: Int) = standardEbooks.search(query, page)
        },
        object : BookCatalog {
            override val id = "gutenberg"
            override val label = "Gutenberg"
            override val note = "Seventy thousand public-domain books."
            override suspend fun browse(page: Int) = gutenberg.browse(page)
            override suspend fun search(query: String, page: Int) = gutenberg.search(query, page)
        },
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
    )

    val default: BookCatalog get() = all.first()

    fun byId(id: String?): BookCatalog = all.firstOrNull { it.id == id } ?: default
}

package com.pagetime.app.ui.screens.library

import com.pagetime.app.data.local.BookEntity

/**
 * What a library item is, because the four kinds are not the same thing and
 * a grid of identical tiles hid it: an EPUB has chapters and a cover of its
 * own, a PDF is text lifted out of a document, a video transcript is speech,
 * and pasted text has no outside source at all.
 */
enum class BookKind(val shelfTitle: String, val note: String) {
    BOOK("On the shelf", "books"),
    PDF("Documents", "PDFs"),
    VIDEO("Watched, now read", "videos"),
    PASTED("Pasted text", "pasted"),
}

object LibraryShelves {

    fun kindOf(book: BookEntity): BookKind = when {
        book.id.startsWith("paste-") -> BookKind.PASTED
        book.id.startsWith("yt-") -> BookKind.VIDEO
        book.format == "pdf" -> BookKind.PDF
        else -> BookKind.BOOK
    }

    /** The few letters shown on a tile, so the kind is clear even out of its shelf. */
    fun badge(book: BookEntity): String = when (kindOf(book)) {
        BookKind.VIDEO -> "VIDEO"
        BookKind.PDF -> "PDF"
        BookKind.PASTED -> "TEXT"
        BookKind.BOOK -> if (book.format == "epub") "EPUB" else book.format.uppercase()
    }

    /** Only shelves that have something on them, in a fixed order, each keeping the order given. */
    fun shelves(books: List<BookEntity>): List<Pair<BookKind, List<BookEntity>>> =
        BookKind.entries.mapNotNull { kind ->
            books.filter { kindOf(it) == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
        }
}

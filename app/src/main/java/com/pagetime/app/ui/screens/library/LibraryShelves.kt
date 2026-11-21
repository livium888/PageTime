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

    /** Read to the end, by the same line the end-of-book offers use. */
    fun isFinished(book: BookEntity): Boolean =
        book.scrollProgress >= com.pagetime.app.data.recommend.ReaderPicks.FINISHED_AT

    /**
     * Only shelves that have something on them, in a fixed order. On each shelf
     * what is still to read comes first and what is finished goes to the bottom,
     * each part keeping the order given (newest first).
     */
    fun shelves(books: List<BookEntity>): List<Pair<BookKind, List<BookEntity>>> =
        BookKind.entries.mapNotNull { kind ->
            books.filter { kindOf(it) == kind }
                // A stable sort: false (unfinished) before true, order within each kept.
                .sortedBy { isFinished(it) }
                .takeIf { it.isNotEmpty() }?.let { kind to it }
        }
}

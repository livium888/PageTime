package com.pagetime.app.ui.screens.settings

import com.pagetime.app.data.local.BookEntity

/**
 * What the library says about how much has been read: books finished, and
 * about how many words.
 *
 * Plain counts, not scores. They are here because how much someone reads over
 * months is a better sign of a reading life than any single sitting, and a
 * minute count cannot tell a fast reader from a slow one.
 *
 * The words are an estimate and called one: each book's length times how far
 * through it the reader is. It cannot know about passages read twice or
 * skipped, and says "about" for that reason.
 */
object ReadingVolume {

    /** Far enough through to count as finished; the last pages of a book are often notes and credits. */
    const val FINISHED_AT = 0.985f

    data class Summary(val booksFinished: Int, val wordsRead: Long) {
        val isEmpty: Boolean get() = booksFinished == 0 && wordsRead == 0L

        val wordsLabel: String
            get() = when {
                wordsRead >= 10_000 -> "about " + "%,d".format(java.util.Locale.US, (wordsRead / 1_000) * 1_000)
                wordsRead > 0 -> "about " + "%,d".format(java.util.Locale.US, (wordsRead / 100) * 100)
                else -> "0"
            }

        val booksLabel: String
            get() = if (booksFinished == 1) "1 book" else "$booksFinished books"
    }

    fun summarize(books: List<BookEntity>): Summary {
        val words = books.sumOf { book ->
            val length = book.wordCount ?: return@sumOf 0L
            (length * book.scrollProgress.coerceIn(0f, 1f)).toLong()
        }
        return Summary(
            booksFinished = books.count { it.scrollProgress >= FINISHED_AT },
            wordsRead = words,
        )
    }
}

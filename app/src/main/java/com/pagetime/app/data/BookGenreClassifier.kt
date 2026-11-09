package com.pagetime.app.data

import com.pagetime.app.data.local.BookDao

/**
 * Tags books with a [BookGenre], when the reader asks for it.
 *
 * Not required for the app to work: untagged books simply stay untagged, and
 * [BookGenreSummary] treats that as "nothing to report". The tagging itself is
 * done by the reader's own AI app — see [BookGenrePrompts] and [ManualAsk].
 */
class BookGenreClassifier(
    private val bookDao: BookDao,
) {
    /** The untagged books a prompt was built for, in the order it numbered them. */
    class PendingGenres internal constructor(
        val prompt: String,
        internal val bookIds: List<String>,
    ) {
        val count: Int get() = bookIds.size
    }

    /** How many books have no genre yet. */
    suspend fun untaggedCount(): Int = bookDao.getUnclassified(Int.MAX_VALUE).size

    /** The prompt for the oldest untagged books, or null when every book is tagged. */
    suspend fun prepare(): PendingGenres? {
        val books = bookDao.getUnclassified(BookGenrePrompts.MAX_BOOKS)
        if (books.isEmpty()) return null
        return PendingGenres(
            prompt = BookGenrePrompts.classifyAll(books.map { it.title to it.author }),
            bookIds = books.map { it.id },
        )
    }

    /**
     * Stores the genres in the reply the reader pasted back and returns how
     * many books were tagged. Fails, changing nothing, when no line of the
     * reply could be read.
     */
    suspend fun apply(pending: PendingGenres, reply: String): Int {
        val genres = BookGenrePrompts.parseAll(reply, pending.count)
        if (genres.isEmpty()) {
            error(
                "No genres could be read from that reply. It should be lines like \"1: Fiction\". " +
                    "Copy the AI's whole reply and try again."
            )
        }
        for ((number, genre) in genres) {
            bookDao.updateGenre(pending.bookIds[number - 1], genre.name)
        }
        return genres.size
    }
}

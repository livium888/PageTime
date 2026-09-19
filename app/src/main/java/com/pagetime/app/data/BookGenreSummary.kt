package com.pagetime.app.data

import com.pagetime.app.data.local.BookEntity

/**
 * Turns the library's classified books into "2 Fiction, 1 Poetry" — the fun,
 * purely descriptive fact this whole feature is for. Books never classified
 * (no AI configured yet, or still waiting their turn — see
 * [BookGenreClassifier]) are simply left out rather than counted as
 * "Unknown": a stat about what the library visibly contains, not a
 * confession of what hasn't been processed yet.
 */
object BookGenreSummary {
    data class Entry(val genre: BookGenre, val count: Int)

    /** Most common genre first; ties keep [BookGenre]'s own declared order. */
    fun summarize(books: List<BookEntity>): List<Entry> =
        books.mapNotNull { book -> book.genre?.let { runCatching { BookGenre.valueOf(it) }.getOrNull() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<BookGenre, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { Entry(it.key, it.value) }

    /** One line for the Library screen, or null when nothing has been classified yet. */
    fun label(entries: List<Entry>): String? {
        if (entries.isEmpty()) return null
        return entries.joinToString(" · ") { "${it.count} ${it.genre.label}" }
    }
}

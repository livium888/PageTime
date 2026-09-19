package com.pagetime.app.data

import com.pagetime.app.data.local.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookGenreSummaryTest {

    private fun book(id: String, genre: BookGenre? = null) = BookEntity(
        id = id,
        title = id,
        author = "",
        format = "epub",
        localPath = "",
        coverUrl = null,
        addedAt = 0L,
        genre = genre?.name,
    )

    @Test
    fun `no classified books means nothing to report`() {
        assertEquals(emptyList<BookGenreSummary.Entry>(), BookGenreSummary.summarize(emptyList()))
        assertNull(BookGenreSummary.label(BookGenreSummary.summarize(listOf(book("1")))))
    }

    @Test
    fun `unclassified books are left out, not counted as a category of their own`() {
        val books = listOf(book("1", BookGenre.FICTION), book("2", genre = null))
        assertEquals(listOf(BookGenreSummary.Entry(BookGenre.FICTION, 1)), BookGenreSummary.summarize(books))
    }

    @Test
    fun `most common genre comes first`() {
        val books = listOf(
            book("1", BookGenre.FICTION),
            book("2", BookGenre.POETRY),
            book("3", BookGenre.FICTION),
        )
        val summary = BookGenreSummary.summarize(books)
        assertEquals(BookGenre.FICTION, summary.first().genre)
        assertEquals(2, summary.first().count)
        assertEquals("2 Fiction · 1 Poetry", BookGenreSummary.label(summary))
    }

    @Test
    fun `a tie keeps BookGenre's own declared order`() {
        // ROMANCE is declared before POETRY, so a 1-1 tie resolves to Romance first.
        val books = listOf(book("1", BookGenre.POETRY), book("2", BookGenre.ROMANCE))
        assertEquals(BookGenre.ROMANCE, BookGenreSummary.summarize(books).first().genre)
    }
}

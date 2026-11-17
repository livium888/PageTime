package com.pagetime.app.ui.screens.library

import com.pagetime.app.data.local.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryShelvesTest {

    private fun book(id: String, format: String = "epub") = BookEntity(
        id = id, title = id, author = "", format = format, localPath = "/x/$id.$format",
        coverUrl = null, addedAt = 0,
    )

    @Test
    fun `each kind is told from the others by its id and format`() {
        assertEquals(BookKind.VIDEO, LibraryShelves.kindOf(book("yt-dQw4w9WgXcQ", "txt")))
        assertEquals(BookKind.VIDEO, LibraryShelves.kindOf(book("yt-5f3c-legacy", "txt")))
        assertEquals(BookKind.PASTED, LibraryShelves.kindOf(book("paste-1", "txt")))
        assertEquals(BookKind.PDF, LibraryShelves.kindOf(book("b1", "pdf")))
        assertEquals(BookKind.BOOK, LibraryShelves.kindOf(book("b2", "epub")))
        assertEquals(BookKind.BOOK, LibraryShelves.kindOf(book("b3", "txt")))
    }

    @Test
    fun `shelves come in a fixed order and only when they hold something`() {
        val shelves = LibraryShelves.shelves(
            listOf(book("yt-a", "txt"), book("b1"), book("paste-1", "txt"), book("b2"))
        )
        assertEquals(listOf(BookKind.BOOK, BookKind.VIDEO, BookKind.PASTED), shelves.map { it.first })
        assertEquals(listOf("b1", "b2"), shelves.first().second.map { it.id })
        assertEquals(emptyList<Any>(), LibraryShelves.shelves(emptyList()))
    }

    @Test
    fun `badges name the kind`() {
        assertEquals("EPUB", LibraryShelves.badge(book("b1")))
        assertEquals("PDF", LibraryShelves.badge(book("b2", "pdf")))
        assertEquals("VIDEO", LibraryShelves.badge(book("yt-a", "txt")))
        assertEquals("TXT", LibraryShelves.badge(book("b3", "txt")))
        assertEquals("TEXT", LibraryShelves.badge(book("paste-1", "txt")))
    }
}

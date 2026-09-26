package com.pagetime.app.data

import com.pagetime.app.data.local.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarianPicksTest {

    private fun book(
        id: String,
        addedAt: Long = 0L,
        scrollProgress: Float = 0f,
        totalReadingSeconds: Long = 0L,
    ) = BookEntity(
        id = id,
        title = id,
        author = "",
        format = "epub",
        localPath = "",
        coverUrl = null,
        addedAt = addedAt,
        scrollProgress = scrollProgress,
        totalReadingSeconds = totalReadingSeconds,
    )

    @Test
    fun `an empty library has nothing to suggest`() {
        assertNull(LibrarianPicks.choose(emptyList()))
    }

    @Test
    fun `a book already finished, or barely started, is not a candidate`() {
        val books = listOf(
            book("finished", scrollProgress = 1f, totalReadingSeconds = 100L),
            book("barely-started", scrollProgress = 0.2f, totalReadingSeconds = 50L),
        )
        assertNull(LibrarianPicks.choose(books))
    }

    @Test
    fun `a never-opened book is offered when nothing is close to finishing`() {
        val books = listOf(book("untouched", addedAt = 100L))
        val pick = LibrarianPicks.choose(books)
        assertEquals("untouched", pick?.book?.id)
        assertEquals(LibrarianPicks.Reason.NEVER_OPENED, pick?.reason)
    }

    @Test
    fun `the oldest-added never-opened book is offered, not just any of them`() {
        val books = listOf(
            book("newer", addedAt = 200L),
            book("older", addedAt = 100L),
        )
        assertEquals("older", LibrarianPicks.choose(books)?.book?.id)
    }

    @Test
    fun `finishing something already underway beats starting something new`() {
        val books = listOf(
            book("untouched", addedAt = 0L),
            book("almost-done", scrollProgress = 0.9f, totalReadingSeconds = 500L),
        )
        val pick = LibrarianPicks.choose(books)
        assertEquals("almost-done", pick?.book?.id)
        assertEquals(LibrarianPicks.Reason.ALMOST_DONE, pick?.reason)
    }

    @Test
    fun `of several almost-done books, the closest to finishing wins`() {
        val books = listOf(
            book("closer", scrollProgress = 0.97f, totalReadingSeconds = 100L),
            book("further", scrollProgress = 0.86f, totalReadingSeconds = 100L),
        )
        assertEquals("closer", LibrarianPicks.choose(books)?.book?.id)
    }

    @Test
    fun `needs a refresh with nothing stored yet, or a stored day that is not today`() {
        assertTrue(LibrarianPicks.needsRefresh(shownEpochDay = null, today = 5L))
        assertTrue(LibrarianPicks.needsRefresh(shownEpochDay = 4L, today = 5L))
        assertTrue(!LibrarianPicks.needsRefresh(shownEpochDay = 5L, today = 5L))
    }
}

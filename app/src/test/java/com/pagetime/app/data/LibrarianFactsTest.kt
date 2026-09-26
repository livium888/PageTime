package com.pagetime.app.data

import com.pagetime.app.data.local.BookEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarianFactsTest {

    private val dayMs = 86_400_000L

    private fun book(addedAt: Long = 0L, scrollProgress: Float = 0f) = BookEntity(
        id = "b",
        title = "The Odyssey",
        author = "Homer",
        format = "epub",
        localPath = "",
        coverUrl = null,
        addedAt = addedAt,
        scrollProgress = scrollProgress,
    )

    @Test
    fun `a never-opened book names the book and says how long it has waited`() {
        val now = 10 * dayMs
        val pick = LibrarianPicks.Pick(book(addedAt = 4 * dayMs), LibrarianPicks.Reason.NEVER_OPENED)
        val sentence = LibrarianFacts.sentence(pick, now)
        assertTrue(sentence.contains("The Odyssey"))
        assertTrue(sentence.contains("6 days ago"))
    }

    @Test
    fun `added today reads as today, not zero days ago`() {
        val now = 5 * dayMs
        val pick = LibrarianPicks.Pick(book(addedAt = now), LibrarianPicks.Reason.NEVER_OPENED)
        assertTrue(LibrarianFacts.sentence(pick, now).contains("today"))
    }

    @Test
    fun `added exactly one week ago is singular, not '1 weeks'`() {
        val now = 7 * dayMs
        val pick = LibrarianPicks.Pick(book(addedAt = 0L), LibrarianPicks.Reason.NEVER_OPENED)
        val sentence = LibrarianFacts.sentence(pick, now)
        assertTrue(sentence.contains("1 week ago"))
        assertTrue(!sentence.contains("1 weeks"))
    }

    @Test
    fun `a book added months ago is described in months, not dozens of days`() {
        val now = 90 * dayMs
        val pick = LibrarianPicks.Pick(book(addedAt = 0L), LibrarianPicks.Reason.NEVER_OPENED)
        assertTrue(LibrarianFacts.sentence(pick, now).contains("3 months ago"))
    }

    @Test
    fun `an almost-done book states the real percentage, not a rounded guess`() {
        val pick = LibrarianPicks.Pick(book(scrollProgress = 0.91f), LibrarianPicks.Reason.ALMOST_DONE)
        val sentence = LibrarianFacts.sentence(pick, 0L)
        assertTrue(sentence.contains("91%"))
        assertTrue(sentence.contains("The Odyssey"))
    }
}

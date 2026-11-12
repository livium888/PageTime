package com.pagetime.app.data.recommend

import com.pagetime.app.data.BookGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPicksTest {

    private val reply = """
        {"numFound": 4, "docs": [
          {"key": "/works/OL1W", "title": "Dune", "author_name": ["Frank Herbert"], "first_publish_year": 1965,
           "ratings_average": 4.3, "ratings_count": 3000},
          {"key": "/works/OL2W", "title": "An Obscure Gem", "ratings_average": 5.0, "ratings_count": 2},
          {"key": "/works/OL3W", "title": "Unrated Book", "author_name": ["Nobody"]},
          {"key": "/works/OL4W", "title": "Small Press Favourite", "author_name": ["A. Writer"],
           "ratings_average": 4.8, "ratings_count": 60}
        ]}
    """.trimIndent()

    @Test
    fun `only works with a rating are read`() {
        val picks = ReaderPicks.parse(reply)
        assertEquals(listOf("Dune", "An Obscure Gem", "Small Press Favourite"), picks.map { it.title })
        assertEquals("Frank Herbert", picks[0].author)
        assertEquals(1965, picks[0].year)
        assertNull(picks[1].author)
    }

    @Test
    fun `an unreadable reply is no picks, not a crash`() {
        assertTrue(ReaderPicks.parse("<html>busy</html>").isEmpty())
        assertTrue(ReaderPicks.parse("{}").isEmpty())
    }

    @Test
    fun `a handful of ratings does not make the list, and many ratings outrank a few`() {
        val ranked = ReaderPicks.rank(ReaderPicks.parse(reply), ownedTitles = emptyList())
        // The 5.0 from two readers is dropped; the 4.3 from thousands leads the 4.8 from sixty.
        assertEquals(listOf("Dune", "Small Press Favourite"), ranked.map { it.title })
    }

    @Test
    fun `a book already on the shelf is not recommended`() {
        val ranked = ReaderPicks.rank(ReaderPicks.parse(reply), ownedTitles = listOf("DUNE: Deluxe Edition"))
        assertEquals(listOf("Small Press Favourite"), ranked.map { it.title })
    }

    @Test
    fun `the same title is listed once`() {
        val a = ReaderPicks.Pick("/works/A", "The Hobbit", "Tolkien", 1937, 4.5f, 5000)
        val b = ReaderPicks.Pick("/works/B", "Hobbit", "Tolkien", 1937, 4.4f, 900)
        assertEquals(listOf("/works/A"), ReaderPicks.rank(listOf(b, a), emptyList()).map { it.key })
    }

    @Test
    fun `the reader's own genres come first and Other is never asked for`() {
        val order = ReaderPicks.genresInOrder(listOf(BookGenre.HISTORY, BookGenre.OTHER, BookGenre.SCIENCE))
        assertEquals(listOf(BookGenre.HISTORY, BookGenre.SCIENCE), order.take(2))
        assertTrue(BookGenre.OTHER !in order)
        assertEquals(BookGenre.entries.size - 1, order.size)
    }

    @Test
    fun `the lines shown under a title`() {
        val pick = ReaderPicks.parse(reply).first()
        assertEquals("4.3 ★ from 3,000 readers", pick.ratingLine)
        assertEquals("Frank Herbert · 1965", pick.byLine)
    }

    @Test
    fun `a finished book recommends from its own genre, else the reader's most read`() {
        assertEquals(BookGenre.HISTORY, ReaderPicks.genreAfterFinishing("HISTORY", listOf(BookGenre.SCIENCE)))
        assertEquals(BookGenre.SCIENCE, ReaderPicks.genreAfterFinishing(null, listOf(BookGenre.SCIENCE)))
        // "Other" cannot be asked for, so it falls through to what the reader reads.
        assertEquals(BookGenre.SCIENCE, ReaderPicks.genreAfterFinishing("OTHER", listOf(BookGenre.SCIENCE)))
        assertEquals(BookGenre.SCIENCE, ReaderPicks.genreAfterFinishing("not a genre", listOf(BookGenre.SCIENCE)))
        assertNull(ReaderPicks.genreAfterFinishing(null, emptyList()))
    }

    @Test
    fun `the genre handed to Discover is taken once`() {
        ReaderPicks.Handoff.ask(BookGenre.POETRY)
        assertEquals(BookGenre.POETRY, ReaderPicks.Handoff.take())
        assertNull(ReaderPicks.Handoff.take())
    }

    @Test
    fun `a work's description, subjects and cover are read`() {
        val about = ReaderPicks.parseAbout(
            """{"description": {"type": "/type/text", "value": "A desert planet and the family sent to rule it."},
                "subjects": ["Science fiction", "science fiction", "Deserts", ""],
                "covers": [-1, 12345]}"""
        )!!
        assertEquals("A desert planet and the family sent to rule it.", about.description)
        assertEquals(listOf("Science fiction", "Deserts"), about.subjects)
        assertEquals("https://covers.openlibrary.org/b/id/12345-M.jpg", about.coverUrl)
    }

    @Test
    fun `a plain-string description is read, and a missing one is not invented`() {
        assertEquals("Short and plain.", ReaderPicks.parseAbout("""{"description": "Short and plain."}""")!!.description)
        val bare = ReaderPicks.parseAbout("""{"title": "Nothing else"}""")!!
        assertNull(bare.description)
        assertTrue(bare.isEmpty)
        assertNull(ReaderPicks.parseAbout("<html>busy</html>"))
    }

    @Test
    fun `the catalogue's housekeeping is cut from a description`() {
        val raw = "A **great** war begins ([source][1]).\n\nSee [the author](https://example.org/a).\n\n" +
            "----------\nAlso contained in:\n - [Collected Works](https://openlibrary.org/works/OL1W)\n\n" +
            "  [1]: https://example.org/source"
        assertEquals("A great war begins.\n\nSee the author.", ReaderPicks.cleanDescription(raw))
        assertNull(ReaderPicks.cleanDescription("   "))
    }
}

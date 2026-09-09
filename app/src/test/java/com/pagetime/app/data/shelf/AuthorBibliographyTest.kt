package com.pagetime.app.data.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsing runs against a live third-party API whose exact responses could
 * not be checked from the build environment, so these fix the shapes it is
 * written against and prove it degrades rather than throws when a field is
 * missing.
 */
class AuthorBibliographyTest {

    private fun candidate(
        key: String,
        name: String,
        works: Int,
        alternates: List<String> = emptyList(),
    ) = AuthorCandidate(key, name, works, null, alternates)

    // --- Choosing the author, which is where a confident lie would happen ---

    @Test
    fun `an exact name match settles it`() {
        val result = AuthorBibliography.chooseAuthor(
            "George Eliot",
            listOf(
                candidate("OL1A", "George Eliot Smith", 400),
                candidate("OL2A", "George Eliot", 120),
            ),
        )
        assertEquals("OL2A", (result as AuthorLookup.Found).author.key)
    }

    /**
     * Two different writers sharing a surname is not a quirk of the data, it
     * is two different people. Showing one person's bibliography under the
     * other's name is a lie the reader cannot catch.
     */
    @Test
    fun `two comparable people with the same name is ambiguous, not a guess`() {
        val result = AuthorBibliography.chooseAuthor(
            "J. Smith",
            listOf(
                candidate("OL1A", "James Smith", 40),
                candidate("OL2A", "Jane Smith", 30),
            ),
        )
        assertTrue(result is AuthorLookup.Ambiguous)
        assertEquals(2, (result as AuthorLookup.Ambiguous).candidates.size)
    }

    @Test
    fun `a clearly dominant record is taken as the author`() {
        val result = AuthorBibliography.chooseAuthor(
            "Charles Dickens",
            listOf(
                candidate("OL1A", "Charles Dickens Jr.", 300),
                candidate("OL2A", "Charles Dickens the younger", 4),
            ),
        )
        assertEquals("OL1A", (result as AuthorLookup.Found).author.key)
    }

    /** The transliteration problem that would have hidden two novels from the ladder. */
    @Test
    fun `an alternate spelling still finds the author`() {
        val result = AuthorBibliography.chooseAuthor(
            "Fyodor Dostoevsky",
            listOf(candidate("OL1A", "Fyodor Dostoyevsky", 200, listOf("Fyodor Dostoevsky"))),
        )
        assertEquals("OL1A", (result as AuthorLookup.Found).author.key)
    }

    @Test
    fun `nobody with that surname is not found rather than the nearest thing`() {
        val result = AuthorBibliography.chooseAuthor(
            "Herman Melville",
            listOf(candidate("OL1A", "Charles Dickens", 300)),
        )
        assertEquals(AuthorLookup.NotFound, result)
    }

    @Test
    fun `a record with no works attached is a stub, not an author`() {
        val result = AuthorBibliography.chooseAuthor(
            "Herman Melville",
            listOf(candidate("OL1A", "H. Melville", 0)),
        )
        assertEquals(AuthorLookup.NotFound, result)
    }

    @Test
    fun `an unusable name cannot match everyone`() {
        assertEquals(AuthorLookup.NotFound, AuthorBibliography.chooseAuthor("", emptyList()))
        assertEquals(
            AuthorLookup.NotFound,
            AuthorBibliography.chooseAuthor("X", listOf(candidate("OL1A", "X Y", 90))),
        )
    }

    // --- Parsing ---

    @Test
    fun `an author search is read into candidates`() {
        val body = """
            {"numFound":2,"docs":[
              {"key":"/authors/OL21594A","name":"George Eliot","work_count":221,
               "top_work":"Middlemarch","alternate_names":["Mary Ann Evans","Marian Evans"]},
              {"key":"OL99A","name":"George Eliot Jr."}
            ]}
        """.trimIndent()
        val docs = AuthorBibliography.parseAuthorSearch(body)
        assertEquals(2, docs.size)
        assertEquals("OL21594A", docs[0].key)
        assertEquals(221, docs[0].workCount)
        assertEquals(listOf("Mary Ann Evans", "Marian Evans"), docs[0].alternateNames)
        // Missing fields degrade rather than throw.
        assertEquals(0, docs[1].workCount)
        assertTrue(docs[1].alternateNames.isEmpty())
        assertNull(docs[1].topWork)
    }

    @Test
    fun `malformed responses come back empty rather than crashing`() {
        assertTrue(AuthorBibliography.parseAuthorSearch("not json").isEmpty())
        assertTrue(AuthorBibliography.parseAuthorSearch("{}").isEmpty())
        assertTrue(AuthorBibliography.parseWorks("not json").isEmpty())
        assertTrue(AuthorBibliography.parseWorks("""{"entries":[]}""").isEmpty())
    }

    /**
     * Open Library carries the same novel several times. Left raw, a shelf
     * reads as forty rows of which twelve are Middlemarch, which teaches the
     * reader the list is junk even where it is accurate.
     */
    @Test
    fun `duplicate titles collapse to one row`() {
        val body = """
            {"entries":[
              {"title":"Middlemarch","key":"/works/OL1W","first_publish_date":"1871"},
              {"title":"middlemarch","key":"/works/OL2W"},
              {"title":"Middlemarch.","key":"/works/OL3W"},
              {"title":"Silas Marner","key":"/works/OL4W","first_publish_date":"June 1861"}
            ]}
        """.trimIndent()
        val works = AuthorBibliography.parseWorks(body)
        assertEquals(2, works.size)
        assertEquals("Middlemarch", works[0].title)
        assertEquals(1871, works[0].firstPublishedYear)
        assertEquals(1861, works[1].firstPublishedYear)
        assertEquals("OL1W", works[0].workKey)
    }

    @Test
    fun `a year is found in whatever shape the date arrives`() {
        assertEquals(1871, AuthorBibliography.yearFrom("1871"))
        assertEquals(1871, AuthorBibliography.yearFrom("June 1871"))
        assertEquals(1871, AuthorBibliography.yearFrom("1871-12-01"))
        assertEquals(2011, AuthorBibliography.yearFrom("2011"))
        assertNull(AuthorBibliography.yearFrom("18??"))
        assertNull(AuthorBibliography.yearFrom(""))
        assertNull(AuthorBibliography.yearFrom(null))
    }

    /** A career reads forwards, and undated work does not pretend to be ancient. */
    @Test
    fun `works are ordered by year with undated ones last`() {
        val ordered = AuthorBibliography.inCareerOrder(
            listOf(
                AuthorWork("Later", 1876, null),
                AuthorWork("Undated", null, null),
                AuthorWork("Earliest", 1858, null),
            )
        )
        assertEquals(listOf("Earliest", "Later", "Undated"), ordered.map { it.title })
    }
}

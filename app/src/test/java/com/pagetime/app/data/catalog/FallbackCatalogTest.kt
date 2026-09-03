package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Gutenberg reaches the app through gutendex.com, a third-party mirror rather
 * than anything Gutenberg runs. One host being unreachable took seventy
 * thousand books out of the app while gutenberg.org served them the whole time.
 */
class FallbackCatalogTest {

    private fun book(title: String) = GutendexBook(
        id = 1L, title = title, authors = emptyList(), downloadCount = 0L,
        epubUrl = "https://example.org/a.epub", txtUrl = null, htmlUrl = null,
        coverUrl = null,
    )

    private class Fake(
        override val id: String,
        private val result: () -> BookPage,
    ) : BookCatalog {
        override val label = id
        override val note = "note"
        var calls = 0
        override suspend fun browse(page: Int) = result().also { calls++ }
        override suspend fun search(query: String, page: Int) = result().also { calls++ }
    }

    private fun page(vararg titles: String) =
        BookPage(titles.map(::book), false, titles.size.toLong())

    @Test
    fun `the preferred route is used and the backup left alone`() {
        val backup = Fake("backup") { page("Backup") }
        val catalog = FallbackCatalog(Fake("gutendex") { page("Preferred") }, backup)
        val result = runBlocking { catalog.search("x", 1) }
        assertEquals(listOf("Preferred"), result.books.map { it.title })
        assertEquals("The backup must not be called when the first route works", 0, backup.calls)
    }

    @Test
    fun `a route that throws falls through to the other one`() {
        val catalog = FallbackCatalog(
            Fake("gutendex") { throw IOException("host is down") },
            Fake("gutenberg-opds") { page("From the fallback") },
        )
        assertEquals(
            listOf("From the fallback"),
            runBlocking { catalog.search("x", 1) }.books.map { it.title },
        )
    }

    @Test
    fun `a route that answers with nothing is also a reason to try the other`() {
        // A mirror can be up and serving empty pages, which looks like success
        // and is the same outcome for the reader as being down.
        val catalog = FallbackCatalog(
            Fake("gutendex") { page() },
            Fake("gutenberg-opds") { page("Found anyway") },
        )
        assertEquals(
            listOf("Found anyway"),
            runBlocking { catalog.search("x", 1) }.books.map { it.title },
        )
    }

    @Test
    fun `when neither has anything the reader sees a search that found nothing`() {
        // Not an error. Both routes agreeing there are no matches is an answer,
        // and dressing it as a failure would send the reader to fix a
        // connection that is fine.
        val catalog = FallbackCatalog(Fake("a") { page() }, Fake("b") { page() })
        assertTrue(runBlocking { catalog.search("zxqv", 1) }.books.isEmpty())
    }

    @Test
    fun `when both routes fail the first failure is what surfaces`() {
        // So the message names the source the reader chose rather than a
        // fallback they never asked for and do not know exists.
        val catalog = FallbackCatalog(
            Fake("gutendex") { throw IOException("gutendex is down") },
            Fake("gutenberg-opds") { throw IOException("gutenberg.org is down") },
        )
        val failure = assertThrows(IOException::class.java) {
            runBlocking { catalog.search("x", 1) }
        }
        assertEquals("gutendex is down", failure.message)
    }

    @Test
    fun `the pair keeps the identity of the route the reader picked`() {
        val catalog = FallbackCatalog(Fake("gutenberg") { page("x") }, Fake("other") { page() })
        assertEquals("gutenberg", catalog.id)
    }
}

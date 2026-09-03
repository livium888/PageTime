package com.pagetime.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared OPDS parser, tested against saved feeds rather than the network.
 *
 * That is not only convenience. A feed URL guessed wrong does not fail to
 * compile — it ships, answers, and shows nothing, which is what makes catalogue
 * work uniquely easy to get silently wrong. Correctness therefore lives in the
 * standard, and these pin the parts of it the app depends on.
 */
class OpdsParserTest {

    private val standardEbooks = BookCatalogs.STANDARD_EBOOKS

    private fun parse(xml: String, feed: OpdsFeed = standardEbooks) =
        OpdsParser.parse(xml.trimIndent().byteInputStream(), feed)

    private val seFeed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/"
              xmlns:media="http://search.yahoo.com/mrss/">
          <link rel="self" href="https://standardebooks.org/feeds/atom/all?query=the&amp;page=1"/>
          <opensearch:totalResults>1398</opensearch:totalResults>
          <opensearch:itemsPerPage>30</opensearch:itemsPerPage>
          <entry>
            <id>https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland</id>
            <title>Alice's Adventures in Wonderland</title>
            <published>1865-11-26T00:00:00Z</published>
            <author><name>Lewis Carroll</name></author>
            <media:thumbnail url="https://standardebooks.org/cover.jpg"/>
            <link rel="enclosure" type="application/epub+zip"
                  title="Advanced epub" href="https://standardebooks.org/advanced.epub"/>
            <link rel="enclosure" type="application/epub+zip"
                  title="Recommended compatible epub" href="https://standardebooks.org/compatible.epub"/>
          </entry>
        </feed>
    """

    @Test
    fun `a Standard Ebooks entry survives the move to the shared parser`() {
        val page = parse(seFeed)
        assertEquals(1, page.books.size)
        val book = page.books.first()
        assertEquals("Alice's Adventures in Wonderland", book.title)
        assertEquals(listOf("Lewis Carroll"), book.authors)
        assertEquals("https://standardebooks.org/cover.jpg", book.coverUrl)
        assertEquals("standardebooks", book.source)
        assertEquals(1865L, book.downloadCount)
        assertEquals(1398L, page.total)
    }

    @Test
    fun `the edition hint wins over the first link offered`() {
        // Standard Ebooks lists an "advanced" epub that not every reader can
        // open. Taking whichever link came first would hand it out.
        assertEquals("https://standardebooks.org/compatible.epub", parse(seFeed).books.first().epubUrl)
    }

    @Test
    fun `ids stay exactly what they were before the parser changed`() {
        // Load-bearing. Book ids are persisted and the downloaded-list is
        // matched by them, so a new derivation would make every book the reader
        // already has look undownloaded. This is the old scheme's value for
        // this entry: 30,000,000 plus the low 24 bits of the path's hash.
        assertEquals(38_588_987L, parse(seFeed).books.first().id)
    }

    @Test
    fun `a feed with no books is a feed with no books, not a failure`() {
        // Feedbooks, still recommended everywhere, shut down in 2024 and its
        // OPDS feed still answers with valid XML and nothing in it. The parser
        // cannot tell that from a search that matched nothing — which is the
        // whole reason CatalogHealth exists and why a feed URL is the one thing
        // worth being careful about.
        val page = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Gone</title></feed>
            """
        )
        assertTrue(page.books.isEmpty())
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `the standard acquisition relations are understood, and buying is not one`() {
        val page = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>urn:one</id><title>Open Access Book</title>
                <link rel="http://opds-spec.org/acquisition/open-access"
                      type="application/epub+zip" href="https://example.org/open.epub"/>
              </entry>
              <entry>
                <id>urn:two</id><title>Plain Acquisition</title>
                <link rel="http://opds-spec.org/acquisition"
                      type="application/epub+zip" href="https://example.org/plain.epub"/>
              </entry>
              <entry>
                <id>urn:three</id><title>For Sale</title>
                <link rel="http://opds-spec.org/acquisition/buy"
                      type="application/epub+zip" href="https://example.org/buy.epub"/>
              </entry>
            </feed>
            """,
            standardEbooks.copy(preferEditions = emptyList())
        )
        assertEquals(listOf("Open Access Book", "Plain Acquisition"), page.books.map { it.title })
    }

    @Test
    fun `an entry offering no EPUB is skipped`() {
        // The common case in academic catalogues, where most titles are PDF
        // only. A reader who cannot open it is not served by seeing it listed.
        val page = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>urn:pdf</id><title>A Monograph</title>
                <link rel="http://opds-spec.org/acquisition/open-access"
                      type="application/pdf" href="https://example.org/mono.pdf"/>
              </entry>
            </feed>
            """
        )
        assertTrue(page.books.isEmpty())
    }

    @Test
    fun `a next link is believed before the arithmetic`() {
        val page = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <link rel="next" href="https://example.org/page2"/>
              <entry>
                <id>urn:a</id><title>A</title>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
                      href="https://example.org/a.epub"/>
              </entry>
            </feed>
            """
        )
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `a next link inside an entry is not the feed's own`() {
        // getElementsByTagNameNS reaches into entries, so an entry that links
        // to the next book would otherwise read as another page of results.
        val page = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>urn:a</id><title>A</title>
                <link rel="next" href="https://example.org/the-next-book"/>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
                      href="https://example.org/a.epub"/>
              </entry>
            </feed>
            """
        )
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `OpenSearch counts carry pagination for feeds that do not link forward`() {
        // Standard Ebooks reports totals and never links to the next page, so
        // the arithmetic is what makes infinite scroll work there.
        assertTrue("page 1 of 1398 has more", parse(seFeed).hasNextPage)
        val lastPage = seFeed.replace("page=1", "page=47")
        assertFalse("page 47 of 1398 at 30 a page is the end", parse(lastPage).hasNextPage)
    }

    @Test
    fun `a catalogue can be held to one language`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <entry>
                <id>urn:fr</id><title>Un Livre</title><dc:language>fr</dc:language>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
                      href="https://example.org/fr.epub"/>
              </entry>
              <entry>
                <id>urn:en</id><title>A Book</title><dc:language>en</dc:language>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
                      href="https://example.org/en.epub"/>
              </entry>
            </feed>
        """
        assertEquals(listOf("A Book"), parse(xml).books.map { it.title })
        val everything = parse(xml, standardEbooks.copy(language = null))
        assertEquals(2, everything.books.size)
    }

    @Test
    fun `an entry that does not say its language is kept`() {
        // Standard Ebooks does not tag entries, and dropping untagged entries
        // would empty the app's default catalogue.
        assertEquals(1, parse(seFeed).books.size)
        assertEquals("en", parse(seFeed).books.first().language)
    }

    @Test
    fun `an entry with no title is not a book`() {
        val page = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>urn:x</id>
                <link rel="http://opds-spec.org/acquisition" type="application/epub+zip"
                      href="https://example.org/x.epub"/>
              </entry>
            </feed>
            """
        )
        assertTrue(page.books.isEmpty())
    }

    @Test
    fun `a feed that needs no browse query is browsable and one that does is too`() {
        // Browsability is now a property of the descriptor rather than a branch
        // that threw. Standard Ebooks browses via a stand-in query.
        assertTrue(OpdsCatalog(standardEbooks).browsable)
        assertFalse(OpdsCatalog(standardEbooks.copy(browseQuery = null)).browsable)
        assertNull(standardEbooks.copy(browseQuery = null).browseQuery)
    }
}

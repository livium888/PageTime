package com.pagetime.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address rules: what the reader typed, and what the address bar said.
 *
 * The two conditions worth most of these tests are the ones that are wrong in
 * every naive implementation of this feature. A host test written with
 * `endsWith` blocks `evilbbc.co.uk` when the rule is `bbc.co.uk`, and a path
 * test written with `startsWith` blocks `/newspaper` when the rule is
 * `/news`. Both are invisible from the screen that created the rule — the
 * reader only finds out by walking into the site, or by being refused one they
 * did not block.
 */
class SiteRulesTest {

    // --- Parsing what the reader typed ---

    @Test
    fun `a bare domain becomes a rule for the whole site`() {
        assertEquals(SiteRules.Rule("bbc.co.uk", null), SiteRules.parse("bbc.co.uk"))
    }

    @Test
    fun `a domain and a path become a rule for that section`() {
        assertEquals(
            SiteRules.Rule("bbc.co.uk", "/news"),
            SiteRules.parse("bbc.co.uk/news"),
        )
    }

    @Test
    fun `a pasted address loses its scheme and its www`() {
        assertEquals(
            SiteRules.Rule("bbc.co.uk", "/news"),
            SiteRules.parse("https://www.bbc.co.uk/news"),
        )
        assertEquals(
            SiteRules.Rule("bbc.co.uk", null),
            SiteRules.parse("http://WWW.BBC.co.uk"),
        )
    }

    @Test
    fun `a trailing slash and a trailing query are not part of the rule`() {
        assertEquals(SiteRules.Rule("bbc.co.uk", "/news"), SiteRules.parse("bbc.co.uk/news/"))
        assertEquals(
            SiteRules.Rule("bbc.co.uk", "/news"),
            SiteRules.parse("bbc.co.uk/news?a=1#top"),
        )
    }

    @Test
    fun `a port is not part of a site`() {
        assertEquals(SiteRules.Rule("bbc.co.uk", null), SiteRules.parse("bbc.co.uk:8080"))
    }

    /**
     * The one refusal that is a judgement call. "news" is what someone types
     * into a search box, and a rule built from it would block every host with
     * `news` in it — a behaviour the reader could not see and would not believe.
     */
    @Test
    fun `a bare word is a search, not a site`() {
        assertNull(SiteRules.parse("news"))
        assertNull(SiteRules.parse("the guardian"))
    }

    @Test
    fun `nothing parses to nothing`() {
        assertNull(SiteRules.parse(""))
        assertNull(SiteRules.parse("   "))
        assertNull(SiteRules.parse("//bbc.co.uk"))
    }

    @Test
    fun `the rule id is the rule written out`() {
        assertEquals("bbc.co.uk", SiteRules.parse("www.bbc.co.uk")?.id)
        assertEquals("bbc.co.uk/news", SiteRules.parse("bbc.co.uk/news/")?.id)
    }

    // --- Reading the address bar ---

    @Test
    fun `a full url reduces to a host and a path`() {
        assertEquals(
            SiteRules.Url("bbc.co.uk", "/news/world"),
            SiteRules.parseUrl("https://www.bbc.co.uk/news/world?x=1#top"),
        )
    }

    @Test
    fun `a root url has an empty path`() {
        assertEquals(SiteRules.Url("bbc.co.uk", ""), SiteRules.parseUrl("bbc.co.uk"))
        assertEquals(SiteRules.Url("bbc.co.uk", ""), SiteRules.parseUrl("bbc.co.uk/"))
        assertEquals(SiteRules.Url("bbc.co.uk", ""), SiteRules.parseUrl("bbc.co.uk?q=1"))
    }

    /**
     * A query belongs to neither the host nor the path. Left attached to the
     * path it makes a section rule stop matching, which reads as the rule
     * having been forgotten rather than as a bug.
     */
    @Test
    fun `a query comes off the path as well as the host`() {
        assertEquals(
            SiteRules.Url("bbc.co.uk", "/news"),
            SiteRules.parseUrl("bbc.co.uk/news?x=1&y=2"),
        )
        assertTrue(SiteRules.matches(bbcNews, url("https://www.bbc.co.uk/news?x=1")))
    }

    /**
     * A browser's address bar shows whatever is in it, and it is not always a
     * site. Every one of these has to come back as "not a URL" rather than as a
     * host that happens to look like one.
     */
    @Test
    fun `a search or an internal page is not a url`() {
        assertNull(SiteRules.parseUrl("how to cook rice"))
        assertNull(SiteRules.parseUrl(""))
        assertNull(SiteRules.parseUrl("about:blank"))
        assertNull(SiteRules.parseUrl("chrome://flags"))
        assertNull(SiteRules.parseUrl("data:text/plain,hello"))
        assertNull(SiteRules.parseUrl("file:///sdcard/book.epub"))
    }

    // --- Matching ---

    private val bbc = SiteRules.Rule("bbc.co.uk", null)
    private val bbcNews = SiteRules.Rule("bbc.co.uk", "/news")

    private fun url(raw: String) = SiteRules.parseUrl(raw)!!

    @Test
    fun `a whole site covers every page on it`() {
        assertTrue(SiteRules.matches(bbc, url("bbc.co.uk")))
        assertTrue(SiteRules.matches(bbc, url("bbc.co.uk/news")))
        assertTrue(SiteRules.matches(bbc, url("bbc.co.uk/news/world/article")))
    }

    @Test
    fun `a whole site covers its subdomains`() {
        assertTrue(SiteRules.matches(bbc, url("www.bbc.co.uk")))
        assertTrue(SiteRules.matches(bbc, url("news.bbc.co.uk")))
        assertTrue(SiteRules.matches(bbc, url("deep.news.bbc.co.uk")))
    }

    /**
     * The bug that a suffix test ships. `evilbbc.co.uk` ends with `bbc.co.uk`
     * as a string and is a different site entirely; blocking it would refuse a
     * page the reader never asked to be kept away from.
     */
    @Test
    fun `a host that merely ends with the rule host is not covered`() {
        assertFalse(SiteRules.matches(bbc, url("evilbbc.co.uk")))
        assertFalse(SiteRules.matches(bbc, url("notbbc.co.uk")))
        assertFalse(SiteRules.matches(bbc, url("bbc.co.uk.evil.com")))
    }

    @Test
    fun `a section covers itself and everything under it`() {
        assertTrue(SiteRules.matches(bbcNews, url("bbc.co.uk/news")))
        assertTrue(SiteRules.matches(bbcNews, url("bbc.co.uk/news/world")))
        assertTrue(SiteRules.matches(bbcNews, url("www.bbc.co.uk/news")))
    }

    /**
     * The other suffix bug. `/news` is a prefix of `/newspaper`, and a reader
     * who blocked the news section did not ask to be kept out of the papers.
     */
    @Test
    fun `a section does not cover a longer word that starts with it`() {
        assertFalse(SiteRules.matches(bbcNews, url("bbc.co.uk/newspaper")))
        assertFalse(SiteRules.matches(bbcNews, url("bbc.co.uk/newsroom")))
    }

    @Test
    fun `a section does not cover the root of the site`() {
        assertFalse(SiteRules.matches(bbcNews, url("bbc.co.uk")))
        assertFalse(SiteRules.matches(bbcNews, url("bbc.co.uk/sport")))
    }

    @Test
    fun `matching ignores case on both sides`() {
        assertTrue(SiteRules.matches(bbc, url("HTTPS://WWW.BBC.CO.UK/News")))
        assertTrue(SiteRules.matches(bbcNews, SiteRules.parseUrl("BBC.co.uk/NEWS")!!))
    }

    // --- Choosing among rules ---

    @Test
    fun `the first rule that covers a url wins`() {
        val rules = listOf(bbc, bbcNews)
        assertEquals(bbc, SiteRules.match("bbc.co.uk/news", rules))
    }

    @Test
    fun `a url no rule covers matches nothing`() {
        assertNull(SiteRules.match("example.com", listOf(bbc, bbcNews)))
    }

    @Test
    fun `an address bar that is not a url matches nothing`() {
        assertNull(SiteRules.match("how to cook rice", listOf(bbc)))
        assertNull(SiteRules.match("bbc.co.uk", emptyList()))
    }
}

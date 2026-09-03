package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads an OPDS (Atom) catalogue feed into book pages.
 *
 * Pure and offline: it takes a stream and gives back a page, so it is tested
 * against saved feeds rather than against the network. That matters more than
 * usual here — the machine this was written on cannot reach any book catalogue
 * at all, and a feed URL guessed wrong does not fail to compile. It ships,
 * answers, and shows nothing. Feedbooks, still recommended everywhere, shut
 * down in 2024 and its feed still returns valid XML with no books in it.
 *
 * So correctness lives in the standard: acquisition relations, OpenSearch
 * pagination and thumbnail relations are all specified, and are what this
 * parses. What remains per-catalogue is a URL, and CatalogHealth is what makes
 * a wrong one visible.
 */
object OpdsParser {

    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val OPENSEARCH = "http://a9.com/-/spec/opensearch/1.1/"
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val EPUB_TYPE = "application/epub+zip"

    /** Relations under which a feed offers the file itself. */
    private val ACQUISITION = setOf(
        "http://opds-spec.org/acquisition",
        "http://opds-spec.org/acquisition/open-access",
        // Plain Atom, which is what Standard Ebooks uses.
        "enclosure",
    )

    /** Relations that are a cover rather than a book. */
    private val IMAGE = setOf(
        "http://opds-spec.org/image/thumbnail",
        "http://opds-spec.org/image",
    )

    fun parse(input: InputStream, feed: OpdsFeed): BookPage {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // A catalogue feed is untrusted input from the network; external
            // entity resolution in an XML parser is how that becomes a file read.
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement

        val books = mutableListOf<GutendexBook>()
        val entries = root.getElementsByTagNameNS(ATOM, "entry")
        for (i in 0 until entries.length) {
            val entry = entries.item(i) as? Element ?: continue
            book(entry, feed)?.let(books::add)
        }

        val total = number(root, OPENSEARCH, "totalResults") ?: 0
        return BookPage(
            books = books,
            hasNextPage = hasNext(root, feed, total),
            total = total.toLong(),
        )
    }

    /**
     * Whether more pages exist.
     *
     * A `rel="next"` link is the direct answer and is preferred wherever a feed
     * provides one. OpenSearch arithmetic is the fallback for feeds that report
     * counts but do not link forward — which is what Standard Ebooks does.
     */
    private fun hasNext(root: Element, feed: OpdsFeed, total: Int): Boolean {
        val links = root.getElementsByTagNameNS(ATOM, "link")
        for (i in 0 until links.length) {
            val link = links.item(i) as? Element ?: continue
            // A feed-level next link only counts when it is not inside an entry.
            if (link.getAttribute("rel") == "next" && link.parentNode === root) return true
        }
        if (total <= 0) return false
        val perPage = number(root, OPENSEARCH, "itemsPerPage") ?: feed.pageSize
        return currentPage(root) * perPage < total
    }

    /** The page this feed represents, read back from its own self link. */
    private fun currentPage(root: Element): Int {
        val links = root.getElementsByTagNameNS(ATOM, "link")
        for (i in 0 until links.length) {
            val link = links.item(i) as? Element ?: continue
            if (link.getAttribute("rel") != "self") continue
            val page = link.getAttribute("href").orEmpty()
                .substringAfter("page=", "")
                .substringBefore("&")
                .toIntOrNull()
            if (page != null) return page
        }
        return 1
    }

    private fun book(entry: Element, feed: OpdsFeed): GutendexBook? {
        val language = text(entry, ATOM, "language") ?: text(entry, DC, "language")
        if (feed.language != null && !language.isNullOrBlank() &&
            !language.startsWith(feed.language, ignoreCase = true)
        ) {
            return null
        }

        val title = text(entry, ATOM, "title")?.takeIf { it.isNotBlank() } ?: return null
        val epub = epubLink(entry, feed) ?: return null
        val entryId = text(entry, ATOM, "id").orEmpty()

        val author = (entry.getElementsByTagNameNS(ATOM, "author").item(0) as? Element)
            ?.let { text(it, ATOM, "name") }
            ?.takeIf { it.isNotBlank() }

        return GutendexBook(
            id = idOf(entryId, feed),
            title = title,
            authors = listOfNotNull(author),
            // Standard Ebooks publishes no download counts, so the year stands
            // in as a sense of the book's era. Feeds without one show nothing.
            downloadCount = text(entry, ATOM, "published")
                ?.take(4)?.toIntOrNull()?.toLong() ?: 0L,
            epubUrl = epub,
            txtUrl = null,
            htmlUrl = null,
            coverUrl = coverLink(entry),
            source = feed.id,
            language = language?.take(2)?.lowercase() ?: feed.language ?: "en",
        )
    }

    /**
     * The EPUB to download, or null when the entry offers none — which is the
     * common case in academic catalogues, where most titles are PDF only.
     */
    private fun epubLink(entry: Element, feed: OpdsFeed): String? {
        var fallback: String? = null
        val links = entry.getElementsByTagNameNS(ATOM, "link")
        val candidates = mutableListOf<Pair<String, String>>() // href to link title
        for (i in 0 until links.length) {
            val link = links.item(i) as? Element ?: continue
            val rel = link.getAttribute("rel").orEmpty()
            if (rel !in ACQUISITION) continue
            if (link.getAttribute("type") != EPUB_TYPE) continue
            val href = link.getAttribute("href").orEmpty()
            if (href.isBlank()) continue
            candidates += href to link.getAttribute("title").orEmpty()
            if (fallback == null) fallback = href
        }
        // Some editions are unreadable on some devices, so a feed offering
        // several gets to say which one it means.
        feed.preferEditions.forEach { hint ->
            candidates.firstOrNull { it.second.contains(hint, ignoreCase = true) }
                ?.let { return it.first }
        }
        return fallback
    }

    private fun coverLink(entry: Element): String? {
        (entry.getElementsByTagNameNS(MEDIA, "thumbnail").item(0) as? Element)
            ?.getAttribute("url")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val links = entry.getElementsByTagNameNS(ATOM, "link")
        for (i in 0 until links.length) {
            val link = links.item(i) as? Element ?: continue
            if (link.getAttribute("rel") in IMAGE) {
                link.getAttribute("href").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    /**
     * A stable Long for an entry whose real id is a URL.
     *
     * Hashed rather than sequential because the app's book table is keyed by
     * Long and a feed gives no numeric id. The offset keeps catalogues in
     * separate ranges; the prefix keeps ids identical across a change of
     * parser, since the reader's downloads are matched by id and a new scheme
     * would make books they already have look undownloaded.
     */
    private fun idOf(entryId: String, feed: OpdsFeed): Long {
        if (feed.numericEntryIds) {
            entryId.takeLastWhile { it.isDigit() }.toLongOrNull()
                ?.let { return feed.idOffset + it }
        }
        val key = feed.idPrefix?.let { entryId.removePrefix(it) } ?: entryId
        return feed.idOffset + (key.hashCode().toLong() and 0xFFFFFF)
    }

    private fun text(parent: Element, ns: String, name: String): String? =
        parent.getElementsByTagNameNS(ns, name).item(0)?.textContent?.trim()

    private fun number(root: Element, ns: String, name: String): Int? =
        text(root, ns, name)?.toIntOrNull()
}

package com.pagetime.app.data.standardebooks

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Client for Standard Ebooks (standardebooks.org).
 *
 * Standard Ebooks is a curated catalog of public-domain ebooks with hand-crafted,
 * high-quality EPUBs. Unlike Project Gutenberg, their servers are consistently
 * reliable, downloads are direct (no 503 retries needed), and every book has a
 * proper EPUB with navigation. This makes them an excellent primary source.
 *
 * The catalog is exposed as an Atom feed:
 *   https://standardebooks.org/feeds/atom/all?query=QUERY&per-page=N&page=P
 * Each entry contains <link rel="enclosure"> elements with direct EPUB URLs.
 */
class StandardEbooksApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 30L)
) {

    /**
     * Browse the catalog. The Standard Ebooks Atom feed requires a query to
     * return results (without one it redirects to an HTML page). We use a
     * broad query ("the") that matches the vast majority of the catalog, giving
     * the user a natural browsing experience. Sorted newest-first.
     */
    suspend fun browse(page: Int = 1): BookPage = fetch(search = BROWSE_QUERY, page = page)

    /** Search the catalog by title/author/subject. */
    suspend fun search(query: String, page: Int = 1): BookPage = fetch(search = query, page = page)

    private suspend fun fetch(search: String?, page: Int): BookPage =
        withContext(Dispatchers.IO) {
            val pageSize = PAGE_SIZE
            val urlBuilder = StringBuilder("$FEED_URL?per-page=$pageSize&page=$page")
            if (!search.isNullOrBlank()) {
                urlBuilder.append("&query=").append(java.net.URLEncoder.encode(search.trim(), "UTF-8"))
            }
            val request = Request.Builder().url(urlBuilder.toString())
                .header("User-Agent", "PageTime/1.0 (Android ebook reader)")
                .get()
                .build()

            // Standard Ebooks is very reliable, but still retry transient failures.
            var lastError: Throwable? = null
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val stream = response.body?.byteStream()
                            if (stream != null) return@withContext parseAtom(stream)
                            lastError = RuntimeException("Empty response from Standard Ebooks")
                            return@use
                        }
                        val code = response.code
                        if (isTransient(code)) {
                            lastError = RuntimeException("Standard Ebooks is busy ($code). Retrying…")
                            return@use
                        }
                        throw RuntimeException("Standard Ebooks request failed ($code)")
                    }
                } catch (e: IOException) {
                    lastError = RuntimeException(
                        "Couldn't reach Standard Ebooks — check your connection", e
                    )
                }
                attempt++
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
            }
            throw lastError ?: RuntimeException("Standard Ebooks request failed")
        }

    private fun isTransient(code: Int): Boolean =
        code == 429 || code == 502 || code == 503 || code == 504 || code >= 500

    private fun parseAtom(input: java.io.InputStream): BookPage {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            try {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Exception) { }
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val root = doc.documentElement

        // OpenSearch metadata for pagination (in the OpenSearch namespace, not Atom)
        val totalResults = root.getElementsByTagNameNS(OPENSEARCH_NS, "totalResults")
            .item(0)?.textContent?.toIntOrNull() ?: 0
        val perPage = root.getElementsByTagNameNS(OPENSEARCH_NS, "itemsPerPage")
            .item(0)?.textContent?.toIntOrNull() ?: PAGE_SIZE
        val hasMore = totalResults > 0 && (pageFromDoc(root) * perPage) < totalResults

        val books = mutableListOf<GutendexBook>()
        val entries = root.getElementsByTagNameNS(ATOM_NS, "entry")
        for (i in 0 until entries.length) {
            val entry = entries.item(i) as? Element ?: continue
            val book = parseEntry(entry) ?: continue
            books.add(book)
        }
        return BookPage(books = books, hasNextPage = hasMore, total = totalResults.toLong())
    }

    private fun pageFromDoc(root: Element): Int {
        // Extract page number from the self link href
        val links = root.getElementsByTagNameNS(ATOM_NS, "link")
        for (i in 0 until links.length) {
            val link = links.item(i) as? Element ?: continue
            if (link.getAttribute("rel") == "self") {
                val href = link.getAttribute("href") ?: ""
                val pageParam = href.substringAfter("page=", "")
                    .substringBefore("&").toIntOrNull()
                if (pageParam != null) return pageParam
            }
        }
        return 1
    }

    private fun parseEntry(entry: Element): GutendexBook? {
        val language = entry.getElementsByTagNameNS(ATOM_NS, "language")
            .item(0)?.textContent?.trim().orEmpty()
        if (language.isNotBlank() && language != "en") return null

        val title = entry.getElementsByTagNameNS(ATOM_NS, "title")
            .item(0)?.textContent?.trim().orEmpty().ifBlank { return null }

        // Author name
        val authorName = entry.getElementsByTagNameNS(ATOM_NS, "author")
            .item(0)?.let { authorNode ->
                (authorNode as Element).getElementsByTagNameNS(ATOM_NS, "name")
                    .item(0)?.textContent?.trim()
            } ?: ""

        // ID is the full URL like https://standardebooks.org/ebooks/lewis-carroll/...
        val idUrl = entry.getElementsByTagNameNS(ATOM_NS, "id")
            .item(0)?.textContent?.trim().orEmpty()
        val id = seIdFromUrl(idUrl)

        // Cover thumbnail
        val coverUrl = entry.getElementsByTagNameNS(MEDIA_NS, "thumbnail")
            .item(0)?.let { (it as Element).getAttribute("url") }
            ?.takeIf { it.isNotBlank() }

        // Find the "Recommended compatible epub" enclosure link
        var epubUrl: String? = null
        val links = entry.getElementsByTagNameNS(ATOM_NS, "link")
        for (i in 0 until links.length) {
            val link = links.item(i) as? Element ?: continue
            if (link.getAttribute("rel") == "enclosure" &&
                link.getAttribute("type") == "application/epub+zip") {
                val titleAttr = link.getAttribute("title") ?: ""
                // Prefer the "compatible" epub (works on all readers) over "advanced"
                if (titleAttr.contains("compatible", ignoreCase = true) ||
                    titleAttr.contains("Recommended", ignoreCase = true)) {
                    epubUrl = link.getAttribute("href")
                    break
                }
                if (epubUrl == null) epubUrl = link.getAttribute("href")
            }
        }
        if (epubUrl.isNullOrBlank()) return null

        // Publication year — used as a display hint (Standard Ebooks doesn't
        // expose download counts; the year gives a sense of the book's era).
        val downloadCount = entry.getElementsByTagNameNS(ATOM_NS, "published")
            .item(0)?.textContent?.substring(0, 4)?.toIntOrNull()?.toLong() ?: 0L

        return GutendexBook(
            id = id,
            title = title,
            authors = if (authorName.isNotBlank()) listOf(authorName) else emptyList(),
            downloadCount = downloadCount,
            epubUrl = epubUrl,
            txtUrl = null,
            htmlUrl = null,
            coverUrl = coverUrl,
            source = "standardebooks"
        )
    }

    companion object {
        private const val FEED_URL = "https://standardebooks.org/feeds/atom/all"
        private const val ATOM_NS = "http://www.w3.org/2005/Atom"
        private const val MEDIA_NS = "http://search.yahoo.com/mrss/"
        private const val OPENSEARCH_NS = "http://a9.com/-/spec/opensearch/1.1/"
        private const val PAGE_SIZE = 30
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 800L
        // Broad query that matches most of the catalog for browse mode.
        private const val BROWSE_QUERY = "the"

        /**
         * Standard Ebooks IDs are URLs; convert to a unique Long that won't
         * collide with Gutenberg (< 10M) or Open Library (20M+ offset).
         * We use a stable hash of the URL path, offset by 30M.
         */
        private fun seIdFromUrl(url: String): Long {
            val path = url.removePrefix("https://standardebooks.org/ebooks/")
            val hash = path.hashCode().toLong() and 0xFFFFFF
            return 30_000_000L + hash
        }
    }
}

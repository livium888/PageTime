package com.pagetime.app.data.openlibrary

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Client for Open Library (openlibrary.org) + Internet Archive downloads.
 *
 * Open Library itself is metadata + catalog — the actual free EPUBs are hosted on
 * archive.org and discovered via the `ia` (Internet Archive) field.  Only books
 * with `public_scan: true` are openly downloadable.
 */
class OpenLibraryApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 60L)
) {

    /** Browse popular public-domain fiction. */
    suspend fun browse(subject: String = "popular", page: Int = 1): BookPage =
        withContext(Dispatchers.IO) {
            val offset = (page - 1) * PAGE_SIZE
            val url = "$BASE/subjects/${subject}.json?limit=$PAGE_SIZE&offset=$offset".buildUrl()
            fetchSubjectPage(url)
        }

    /** Full-text search — only public-domain / openly downloadable books are returned. */
    suspend fun search(query: String, page: Int = 1): BookPage =
        withContext(Dispatchers.IO) {
            val offset = (page - 1) * PAGE_SIZE
            val url = "$BASE/search.json?q=${query.urlEncode()}&limit=$PAGE_SIZE&offset=$offset".buildUrl()
            fetchSearchPage(url)
        }

    // ─────────────────────── internal ───────────────────────

    private fun fetchSubjectPage(url: String): BookPage {
        val body = httpGet(url)
        val root = JSONObject(body)
        val works = root.optJSONArray("works") ?: return BookPage(emptyList(), false, 0)
        val books = mutableListOf<GutendexBook>()
        for (i in 0 until works.length()) {
            val w = works.optJSONObject(i) ?: continue
            val ia = w.optString("ia").ifBlank { null } ?: continue
            val coverId = w.optLong("cover_id", 0L)
            books.add(
                GutendexBook(
                    id = olKeyToId(w.optString("key", "")),
                    title = w.optString("title", "Untitled"),
                    authors = w.optJSONArray("authors")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    } ?: emptyList(),
                    downloadCount = w.optInt("edition_count", 0).toLong(),
                    epubUrl = ia.epubUrl(),
                    txtUrl = ia.txtUrl(),
                    htmlUrl = null,
                    coverUrl = coverId.coverUrl(),
                    source = "openlibrary"
                )
            )
        }
        val nextOffset = root.optInt("work_count", 0)
        return BookPage(books = books, hasNextPage = nextOffset > 0, total = nextOffset.toLong())
    }

    private fun fetchSearchPage(url: String): BookPage {
        val body = httpGet(url)
        val root = JSONObject(body)
        val docs = root.optJSONArray("docs") ?: return BookPage(emptyList(), false, 0)
        val books = mutableListOf<GutendexBook>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val iaArr = d.optJSONArray("ia")
            val ia = iaArr?.optString(0)?.ifBlank { null } ?: continue
            val coverId = d.optLong("cover_i", 0L)
            books.add(
                GutendexBook(
                    id = olKeyToId(d.optString("key", "")),
                    title = d.optString("title", "Untitled"),
                    authors = d.optJSONArray("author_name")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it) }
                    } ?: emptyList(),
                    downloadCount = d.optInt("edition_count", 0).toLong(),
                    epubUrl = ia.epubUrl(),
                    txtUrl = ia.txtUrl(),
                    htmlUrl = null,
                    coverUrl = coverId.coverUrl(),
                    source = "openlibrary"
                )
            )
        }
        val total = root.optInt("num_found", 0)
        val offset = root.optInt("offset", 0)
        return BookPage(books = books, hasNextPage = offset + PAGE_SIZE < total, total = total.toLong())
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "PageTime/1.0 (Android ebook reader)")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Open Library request failed (${response.code})")
            response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val BASE = "https://openlibrary.org"
        private const val IA_BASE = "https://archive.org/download"
        private const val COVERS = "https://covers.openlibrary.org/b"
        private const val PAGE_SIZE = 32

        /** Open Library keys look like /works/OL12345W — extract the numeric part. */
        private fun olKeyToId(key: String): Long {
            val num = key.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
            // Offset by 20 000 000 so there's no collision with Gutenberg IDs (< 10 M).
            return 20_000_000L + num
        }

        private fun String.epubUrl(): String? = "$IA_BASE/$this/$this.epub"
        private fun String.txtUrl(): String? = "$IA_BASE/$this/${this}_djvu.txt"
        private fun Long.coverUrl(): String? = if (this > 0) "$COVERS/id/$this-L.jpg" else null
        private fun String.buildUrl(): String = this.replace(" ", "%20")

        /** Minimal percent-encoding for query strings. */
        private fun String.urlEncode(): String =
            java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}

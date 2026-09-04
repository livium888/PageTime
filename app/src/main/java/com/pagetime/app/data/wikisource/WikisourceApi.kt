package com.pagetime.app.data.wikisource

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Wikisource, the Wikimedia library of transcribed texts.
 *
 * Worth having for what it holds that the other catalogues do not: works
 * transcribed and proofread by hand, a great deal outside English, and a long
 * tail of documents, speeches and pamphlets that were never commercial books.
 *
 * It is not an OPDS catalogue and has no feed to page through, so this is two
 * services rather than one. MediaWiki's search says which pages exist; WS
 * Export turns a named page into an EPUB on request. The download link is
 * therefore an instruction to build a file rather than a pointer at one that
 * already exists — which is why the first download of a long work can be slow,
 * and why this is the one catalogue here whose exporter is known to have
 * uptime trouble.
 */
class WikisourceApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 45L),
    private val language: String = "en",
) {

    /**
     * There is no browse. Wikisource has no notion of a most-read or newest
     * work to lead with, and inventing one — a broad search for a common word,
     * as Standard Ebooks needs — would present an arbitrary slice as if it were
     * a front page. The catalogue says it needs a query and the shelf tells
     * the reader so.
     */
    suspend fun search(query: String, page: Int = 1): BookPage = withContext(Dispatchers.IO) {
        val offset = (page - 1) * PAGE_SIZE
        val url = "https://$language.wikisource.org/w/api.php" +
            "?action=query&list=search&format=json&formatversion=2" +
            // Namespace 0 is the main text namespace: it leaves out Author:,
            // Portal: and the project's own pages, which are not works.
            "&srnamespace=0&srwhat=text" +
            "&srsearch=${query.trim().encode()}" +
            "&srlimit=$PAGE_SIZE&sroffset=$offset"

        val root = JSONObject(get(url))
        val results = root.optJSONObject("query")?.optJSONArray("search")
            ?: return@withContext BookPage(emptyList(), false, 0)

        val books = buildList {
            for (i in 0 until results.length()) {
                val hit = results.optJSONObject(i) ?: continue
                val title = hit.optString("title").trim()
                if (title.isBlank()) continue
                // A subpage is one chapter of a work, not the work. Exporting
                // the parent gives the whole book, so listing both would offer
                // the reader a book and its own third chapter side by side.
                if (title.contains('/')) continue
                add(
                    GutendexBook(
                        id = stableId(title),
                        title = title,
                        authors = emptyList(),
                        downloadCount = 0L,
                        epubUrl = exportUrl(title),
                        txtUrl = null,
                        htmlUrl = "https://$language.wikisource.org/wiki/${title.encode()}",
                        coverUrl = null,
                        source = "wikisource",
                        language = language,
                    )
                )
            }
        }

        val total = root.optJSONObject("query")
            ?.optJSONObject("searchinfo")?.optInt("totalhits", 0) ?: 0
        BookPage(books, offset + PAGE_SIZE < total, total.toLong(), considered = results.length())
    }

    /** Asks WS Export to build an EPUB of [title]. */
    private fun exportUrl(title: String): String =
        "$EXPORT?format=epub&lang=$language&page=${title.encode()}"

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Wikisource answered ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private const val EXPORT = "https://ws-export.wmcloud.org/"
        private const val USER_AGENT = "PageTime/1.0 (Android ebook reader)"
        private const val PAGE_SIZE = 25

        /** Its own range, clear of Gutenberg, Open Library, Standard Ebooks and the Archive. */
        internal fun stableId(title: String): Long =
            50_000_000L + (title.hashCode().toLong() and 0xFFFFFF)
    }
}

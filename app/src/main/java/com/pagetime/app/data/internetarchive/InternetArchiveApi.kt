package com.pagetime.app.data.internetarchive

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Internet Archive search, narrowed to books that can actually be read here.
 *
 * The archive is mostly page scans. A text item typically holds djvu, jp2 and a
 * PDF, and an EPUB exists for only some of them — so a plain search returned
 * thirty results of which nearly none had an EPUB, every one was fetched and
 * inspected one after another, and what reached the reader was an empty shelf
 * after a long wait.
 *
 * Two changes fix that. The search itself now asks only for items holding an
 * EPUB, so the candidates are worth checking; and the checks run together
 * rather than in sequence, through the same resolver Open Library uses, which
 * also drops the lending books whose downloads answer 403.
 */
class InternetArchiveApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 60L),
    private val files: InternetArchiveFiles = InternetArchiveFiles(),
) {

    suspend fun search(query: String, page: Int = 1): BookPage = withContext(Dispatchers.IO) {
        val escaped = query.trim().replace("\\", "\\\\").replace("\"", "\\\"")
        // format:(EPUB) is what stops the archive offering its scan-only
        // majority. NOT access-restricted-item asks it to leave out the lending
        // books before they are counted, so a page of results is a page of
        // books rather than a page of things to reject.
        val fullQuery =
            "($escaped) AND mediatype:texts AND format:(EPUB) AND NOT access-restricted-item:true"
        val url = "$SEARCH?q=${fullQuery.urlEncode()}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator" +
            "&rows=$PAGE_SIZE&page=$page&output=json"

        val response = JSONObject(get(url)).optJSONObject("response")
            ?: return@withContext BookPage(emptyList(), false, 0)
        val docs = response.optJSONArray("docs")
            ?: return@withContext BookPage(emptyList(), false, 0)

        val candidates = buildList {
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i) ?: continue
                val identifier = doc.optString("identifier").trim()
                if (identifier.isNotBlank()) add(doc to identifier)
            }
        }

        val resolved = files.resolveAll(candidates.map { it.second })
        val books = candidates.mapNotNull { (doc, identifier) ->
            val downloads = resolved[identifier] ?: return@mapNotNull null
            GutendexBook(
                id = stableId(identifier),
                title = doc.optString("title", identifier),
                authors = authors(doc.opt("creator")),
                downloadCount = 0L,
                epubUrl = downloads.epubUrl,
                txtUrl = downloads.txtUrl,
                htmlUrl = null,
                coverUrl = "https://archive.org/services/img/$identifier",
                source = "internetarchive",
                language = "en",
            )
        }

        val total = response.optInt("numFound", 0)
        BookPage(books, (page * PAGE_SIZE) < total, total.toLong())
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "PageTime/1.0 (Android ebook reader)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Internet Archive answered ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun authors(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is org.json.JSONArray ->
            (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private const val SEARCH = "https://archive.org/advancedsearch.php"
        private const val PAGE_SIZE = 24

        internal fun stableId(identifier: String): Long =
            40_000_000L + (identifier.hashCode().toLong() and 0xFFFFFF)
    }
}

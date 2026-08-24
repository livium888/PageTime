package com.pagetime.app.data.internetarchive

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Internet Archive AdvancedSearch + metadata API client for openly downloadable books. */
class InternetArchiveApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 60L)
) {
    suspend fun search(query: String, page: Int = 1): BookPage = withContext(Dispatchers.IO) {
        val pageSize = 24
        val escaped = query.trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val fullQuery = "($escaped) AND mediatype:texts"
        val url = "https://archive.org/advancedsearch.php" +
            "?q=${fullQuery.urlEncode()}&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=description" +
            "&rows=$pageSize&page=$page&output=json"
        val body = get(url)
        val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
            ?: return@withContext BookPage(emptyList(), false, 0)
        val books = mutableListOf<GutendexBook>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optString("identifier").trim()
            if (identifier.isBlank()) continue
            val files = runCatching { get("https://archive.org/metadata/$identifier") }
                .getOrNull()?.let { JSONObject(it).optJSONArray("files") } ?: continue
            val epub = findFile(files, ".epub") ?: continue
            val txt = findFile(files, ".txt")
            books += GutendexBook(
                id = stableId(identifier),
                title = doc.optString("title", identifier),
                authors = authors(doc.opt("creator")),
                downloadCount = 0L,
                epubUrl = "https://archive.org/download/$identifier/${epub.first}",
                txtUrl = txt?.let { "https://archive.org/download/$identifier/${it.first}" },
                htmlUrl = null,
                coverUrl = "https://archive.org/services/img/$identifier",
                source = "internetarchive",
                language = "en"
            )
        }
        val response = JSONObject(body).optJSONObject("response")
        val total = response?.optInt("numFound", 0) ?: 0
        BookPage(books, (page * pageSize) < total, total.toLong())
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "PageTime/1.0 (Android ebook reader)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Internet Archive request failed (${response.code})")
            return response.body?.string().orEmpty()
        }
    }

    private fun findFile(files: org.json.JSONArray, extension: String): Pair<String, Long>? {
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.optString("name")
            if (name.endsWith(extension, ignoreCase = true) && file.optLong("size", 1L) > 0L) {
                return name to file.optLong("size")
            }
        }
        return null
    }

    private fun authors(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private fun stableId(identifier: String): Long = 40_000_000L + (identifier.hashCode().toLong() and 0xFFFFFF)
    }
}

package com.pagetime.app.data.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class GutenbergApi(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun search(query: String, page: Int = 1): List<GutendexBook> =
        withContext(Dispatchers.IO) {
            val url = "https://gutendex.com/books/".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("search", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Search failed (${response.code})")
                }
                val body = response.body?.string().orEmpty()
                parseResults(body)
            }
        }

    private fun parseResults(body: String): List<GutendexBook> {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<GutendexBook>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue

            val authors = mutableListOf<String>()
            o.optJSONArray("authors")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(authors::add)
                }
            }

            val formats = o.optJSONObject("formats") ?: JSONObject()
            val txtUrl = formats.optString("text/plain; charset=utf-8").ifBlank { null }
                ?: formats.optString("text/plain; charset=us-ascii").ifBlank { null }
                ?: formats.optString("text/plain").ifBlank { null }

            out.add(
                GutendexBook(
                    id = o.optLong("id"),
                    title = o.optString("title", "Untitled"),
                    authors = authors,
                    downloadCount = o.optLong("download_count"),
                    epubUrl = formats.optString("application/epub+zip").ifBlank { null },
                    txtUrl = txtUrl,
                    htmlUrl = formats.optString("text/html").ifBlank { null },
                    coverUrl = formats.optString("image/jpeg").ifBlank { null }
                )
            )
        }
        return out
    }
}

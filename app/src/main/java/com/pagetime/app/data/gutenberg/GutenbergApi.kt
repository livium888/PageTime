package com.pagetime.app.data.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Client for the Gutendex API (https://gutendex.com/), the standard open catalog for
 * Project Gutenberg. With no search term, Gutendex returns the most-downloaded books,
 * which makes a great default browse list.
 */
class GutenbergApi(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun browse(page: Int = 1): BookPage = fetch(search = null, page = page)

    suspend fun search(query: String, page: Int = 1): BookPage = fetch(search = query, page = page)

    private suspend fun fetch(search: String?, page: Int): BookPage =
        withContext(Dispatchers.IO) {
            val url = "https://gutendex.com/books/".toHttpUrlOrNull()!!.newBuilder()
                .apply {
                    if (!search.isNullOrBlank()) addQueryParameter("search", search.trim())
                    if (page > 1) addQueryParameter("page", page.toString())
                }
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Gutenberg request failed (${response.code})")
                }
                parsePage(response.body?.string().orEmpty())
            }
        }

    private fun parsePage(body: String): BookPage {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return BookPage(emptyList(), false, 0)
        val books = mutableListOf<GutendexBook>()
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

            books.add(
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
        return BookPage(
            books = books,
            hasNextPage = root.optString("next").isNotBlank(),
            total = root.optLong("count")
        )
    }
}

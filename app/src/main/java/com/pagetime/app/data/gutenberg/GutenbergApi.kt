package com.pagetime.app.data.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.pagetime.app.data.AppHttp
import java.io.IOException

/**
 * Client for the Gutendex API (https://gutendex.com/), the standard open catalog for
 * Project Gutenberg. With no search term, Gutendex returns the most-downloaded books,
 * which makes a great default browse list.
 */
class GutenbergApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 90L)
) {

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
            val request = Request.Builder().url(url).get().build()

            // Gutendex is a free, shared instance of the Gutenberg catalog API and is
            // frequently rate-limited or "busy" (503/502/429) under load, especially for
            // the multi-thousand-book browse pagination. Retry transient failures a few
            // times with backoff instead of surfacing a hard error on the first busy
            // moment. Non-transient statuses (e.g. 404) fail immediately.
            var lastError: Throwable? = null
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext parsePage(response.body?.string().orEmpty())
                        }
                        val code = response.code
                        if (isTransient(code)) {
                            lastError = RuntimeException("Gutenberg is busy right now ($code). Retrying…")
                            return@use
                        }
                        throw RuntimeException("Gutenberg request failed ($code)")
                    }
                } catch (e: IOException) {
                    // Network-level failure (DNS, no route, timeout). Retryable.
                    lastError = RuntimeException(
                        "Couldn't reach Project Gutenberg — check your connection",
                        e
                    )
                }
                attempt++
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
            }
            throw lastError ?: RuntimeException("Gutenberg request failed")
        }

    private fun isTransient(code: Int): Boolean =
        code == 429 || code == 502 || code == 503 || code == 504 || code >= 500

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 700L
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
            val languages = o.optJSONArray("languages")
            val language = languages?.optString(0).orEmpty()
            if (language.isNotBlank() && language != "en") continue
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

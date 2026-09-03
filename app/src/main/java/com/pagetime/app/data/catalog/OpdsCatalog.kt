package com.pagetime.app.data.catalog

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * A catalogue reached over OPDS.
 *
 * The fetching, retrying and paging that used to sit inside one hand-written
 * client now serve every OPDS source, so a new one is an [OpdsFeed] rather than
 * a class.
 */
class OpdsCatalog(
    private val feed: OpdsFeed,
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 30L),
) : BookCatalog {

    override val id: String get() = feed.id
    override val label: String get() = feed.label
    override val note: String get() = feed.note

    /** A feed with no stand-in query has nothing to show without a search. */
    override val browsable: Boolean get() = feed.browseQuery != null

    override suspend fun browse(page: Int): BookPage =
        fetch(feed.browseQuery, page)

    override suspend fun search(query: String, page: Int): BookPage =
        fetch(query, page)

    private suspend fun fetch(query: String?, page: Int): BookPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append(feed.url)
            var separator = if (feed.url.contains('?')) '&' else '?'
            feed.pageSizeParam?.let {
                append(separator).append(it).append('=').append(feed.pageSize)
                separator = '&'
            }
            // OpenSearch counts its start index from one, not zero, so an
            // offset-paged feed is asked for item 1, 26, 51 rather than 0, 25,
            // 50. Taken from the spec because no catalogue here can be reached
            // to check; a feed that disagrees now says so through CatalogHealth
            // rather than silently serving page one forever.
            val position = if (feed.pageIsOffset) (page - 1) * feed.pageSize + 1 else page
            append(separator).append(feed.pageParam).append('=').append(position)
            if (!query.isNullOrBlank()) {
                append('&').append(feed.queryParam).append('=')
                append(URLEncoder.encode(query.trim(), "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val stream = response.body?.byteStream()
                        if (stream != null) return@withContext OpdsParser.parse(stream, feed)
                        lastError = IOException("${feed.label} returned an empty response")
                        return@use
                    }
                    if (isTransient(response.code)) {
                        lastError = IOException("${feed.label} is busy (${response.code})")
                        return@use
                    }
                    // A permanent code will not improve on a retry. A feed that
                    // has moved or been withdrawn answers 404 forever, and this
                    // is what carries that to the reader instead of a blank
                    // list. Deliberately not an IOException: the catch below
                    // treats those as worth retrying, so throwing one here
                    // would spend three attempts on a settled answer.
                    throw CatalogRefused("${feed.label} answered ${response.code}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            attempt++
            if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
        }
        throw lastError ?: IOException("${feed.label} did not answer")
    }

    private fun isTransient(code: Int): Boolean = code == 429 || code >= 500

    private companion object {
        const val USER_AGENT = "PageTime/1.0 (Android ebook reader)"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 800L
    }
}

/** A catalogue answered, and its answer was no. Never retried. */
class CatalogRefused(message: String) : RuntimeException(message)

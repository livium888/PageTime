package com.pagetime.app.data.openlibrary

import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.internetarchive.InternetArchiveFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Client for Open Library (openlibrary.org), whose books are files on archive.org.
 *
 * Open Library is a catalogue, not a library: an entry's "ia" field says the
 * work exists on archive.org, and nothing more. Whether the files can be
 * downloaded is a separate question, and the answer is often no — the item may
 * be a lending book, or may be page scans with no EPUB ever generated.
 *
 * Every listed book is therefore resolved against the item itself before it is
 * shown, so the shelf offers only what it can actually deliver. The previous
 * version guessed a URL from the identifier and offered every result, which is
 * why downloads answered 403 and 404.
 */
class OpenLibraryApi(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 60L),
    private val files: InternetArchiveFiles = InternetArchiveFiles(),
) {

    /**
     * Browse public-domain fiction.
     *
     * Goes through search rather than the subjects endpoint, which has no way
     * to ask for openly downloadable books — so browsing used to list lending
     * books that could never be downloaded while searching did not.
     */
    suspend fun browse(subject: String = "fiction", page: Int = 1): BookPage =
        fetch("subject:$subject", page)

    suspend fun search(query: String, page: Int = 1): BookPage = fetch(query, page)

    private suspend fun fetch(query: String, page: Int): BookPage = withContext(Dispatchers.IO) {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$BASE/search.json" +
            "?q=${query.urlEncode()}" +
            "&fields=$FIELDS" +
            "&limit=$PAGE_SIZE&offset=$offset" +
            // Asks the catalogue to leave out what it knows is not free. It is
            // a filter on intent, not a guarantee about files, so every
            // survivor is still checked against its archive.org item below.
            "&ebook_access=public"
        val root = JSONObject(httpGet(url))
        val docs = root.optJSONArray("docs") ?: return@withContext BookPage(emptyList(), false, 0)

        val candidates = buildList {
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i) ?: continue
                if (!isEnglish(doc)) continue
                val ia = doc.optJSONArray("ia")?.optString(0)?.takeIf { it.isNotBlank() } ?: continue
                add(doc to ia)
            }
        }

        val resolved = files.resolveAll(candidates.map { it.second })
        val books = candidates.mapNotNull { (doc, ia) ->
            val downloads = resolved[ia] ?: return@mapNotNull null
            val coverId = doc.optLong("cover_i", 0L)
            GutendexBook(
                id = olKeyToId(doc.optString("key", "")),
                title = doc.optString("title", "Untitled"),
                authors = doc.optJSONArray("author_name")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                downloadCount = doc.optInt("edition_count", 0).toLong(),
                epubUrl = downloads.epubUrl,
                txtUrl = downloads.txtUrl,
                htmlUrl = null,
                coverUrl = if (coverId > 0) "$COVERS/id/$coverId-L.jpg" else null,
                source = "openlibrary",
            )
        }

        val total = root.optInt("num_found", 0)
        // Paging follows the catalogue's own count, not what survived the
        // filter: a page where most books turned out to be unavailable still
        // has pages after it, and stopping there would hide the rest.
        BookPage(books = books, hasNextPage = offset + PAGE_SIZE < total, total = total.toLong())
    }

    private fun isEnglish(doc: JSONObject): Boolean {
        val languages = doc.optJSONArray("language") ?: return true
        return (0 until languages.length()).any {
            languages.optString(it).let { value -> value == "eng" || value == "en" }
        }
    }

    private suspend fun httpGet(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "PageTime/1.0 (Android ebook reader)")
            .get()
            .build()
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string().orEmpty()
                    if (isTransient(response.code)) {
                        lastError = IOException("Open Library is busy (${response.code})")
                        return@use
                    }
                    throw OpenLibraryRefused("Open Library answered ${response.code}")
                }
            } catch (e: IOException) {
                lastError = IOException("Couldn't reach Open Library — check your connection", e)
            }
            attempt++
            if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
        }
        throw lastError ?: IOException("Open Library did not answer")
    }

    private fun isTransient(code: Int): Boolean = code == 429 || code >= 500

    companion object {
        private const val BASE = "https://openlibrary.org"
        private const val COVERS = "https://covers.openlibrary.org/b"
        private const val PAGE_SIZE = 32
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 800L

        /** Only the fields used below; the default response carries far more. */
        private const val FIELDS = "key,title,author_name,ia,cover_i,edition_count,language"

        /** Open Library keys look like /works/OL12345W — keep the numeric part. */
        internal fun olKeyToId(key: String): Long {
            val num = key.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
            // Offset by 20,000,000 so there is no collision with Gutenberg (< 10M).
            return 20_000_000L + num
        }

        private fun String.urlEncode(): String =
            java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}

/** Open Library answered, and its answer was no. Never retried. */
class OpenLibraryRefused(message: String) : RuntimeException(message)

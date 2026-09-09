package com.pagetime.app.data.shelf

import com.pagetime.app.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Open Library's author endpoints: who wrote what.
 *
 * Chosen over asking a language model because a bibliography is a set of
 * factual claims about what exists, and this returns rows. See
 * [AuthorBibliography] for the longer argument.
 *
 * No API key and no cost per call, which also means an author lookup does not
 * have to be rationed the way a Gemini call does.
 *
 * ONE ATTEMPT, NOT FOUR
 *
 * Unlike the book catalogues, this is a convenience: the reader tapped an
 * author's name out of curiosity. Retrying a slow or unhappy server three
 * times over several seconds for that would be spending their patience on
 * something they can simply tap again.
 */
class OpenLibraryAuthors(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 20L),
) {

    companion object {
        private const val BASE = "https://openlibrary.org"

        /**
         * Enough of a career to be worth reading, and few enough to be worth
         * looking at. Prolific writers of serials run to hundreds of entries,
         * most of which are the same story filed differently.
         */
        const val MAX_WORKS = 60
    }

    /** Candidates for a name, or empty when the lookup could not be made. */
    suspend fun searchAuthors(name: String): List<AuthorCandidate> = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext emptyList()
        val url = "$BASE/search/authors.json".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", name.trim())
            ?.build() ?: return@withContext emptyList()
        AuthorBibliography.parseAuthorSearch(get(url.toString()) ?: return@withContext emptyList())
    }

    /** The author's works, deduplicated and in career order. */
    suspend fun works(authorKey: String): List<AuthorWork> = withContext(Dispatchers.IO) {
        if (authorKey.isBlank()) return@withContext emptyList()
        val url = "$BASE/authors/$authorKey/works.json".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("limit", MAX_WORKS.toString())
            ?.build() ?: return@withContext emptyList()
        val body = get(url.toString()) ?: return@withContext emptyList()
        AuthorBibliography.inCareerOrder(AuthorBibliography.parseWorks(body))
    }

    /**
     * The body, or null.
     *
     * Every failure is one answer here — unreachable, refused, empty — because
     * the caller does exactly the same thing with all of them: tells the
     * reader it could not look this author up. Distinguishing them would put a
     * network error on a screen about books.
     */
    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()
}

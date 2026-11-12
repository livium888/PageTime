package com.pagetime.app.data.recommend

import com.pagetime.app.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks Open Library for the well-rated books of a subject.
 *
 * One search, sorted by rating, taking more than will be shown so that
 * [ReaderPicks.rank] has enough to choose from after dropping the thinly rated
 * and the already owned. Null when the catalogue could not be asked, which the
 * screen says plainly; an empty list means it answered and had nothing.
 */
class OpenLibraryPicks(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 30L),
) {
    suspend fun forSubject(subject: String): List<ReaderPicks.Pick>? = withContext(Dispatchers.IO) {
        // The subject as a field first; if the catalogue finds nothing that
        // way, the same words as a plain search.
        val asField = fetch("subject:\"$subject\"") ?: return@withContext null
        if (asField.isNotEmpty()) asField else fetch(subject)
    }

    /** What the catalogue says about one work; null when it could not be asked. */
    suspend fun about(workKey: String): ReaderPicks.About? = withContext(Dispatchers.IO) {
        val key = workKey.trim().trim('/')
        if (!key.matches(Regex("works/OL\\d+W"))) return@withContext null
        val body = runCatching {
            client.newCall(Request.Builder().url("https://openlibrary.org/$key.json").get().build())
                .execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        ReaderPicks.parseAbout(body)
    }

    private fun fetch(query: String): List<ReaderPicks.Pick>? {
        val url = "https://openlibrary.org/search.json".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("sort", "rating")
            ?.addQueryParameter("limit", "100")
            ?.addQueryParameter(
                "fields",
                "key,title,author_name,first_publish_year,ratings_average,ratings_count",
            )
            ?.build() ?: return null
        val body = runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull() ?: return null
        return ReaderPicks.parse(body)
    }
}

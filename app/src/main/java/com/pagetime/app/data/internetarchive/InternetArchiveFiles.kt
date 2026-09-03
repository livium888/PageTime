package com.pagetime.app.data.internetarchive

import com.pagetime.app.data.AppHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Turns an archive.org item id into files the reader can actually download.
 *
 * WHY THIS EXISTS
 *
 * Open Library built its download links by guessing:
 *
 *     "https://archive.org/download/$id/$id.epub"
 *
 * Nothing checked that the file existed, and nothing checked the item was
 * downloadable at all. Both assumptions are wrong often enough to be the
 * reason downloads failed:
 *
 *  - Most archive.org text items have no EPUB. They are page scans, so what
 *    they hold is djvu, jp2 and a PDF, and the EPUB is derived for only some
 *    of them. A guessed name for a file that was never generated is a 404.
 *
 *  - Many items are lending-library books. The Internet Archive lends those
 *    one copy at a time under controlled digital lending, so the files are
 *    access-restricted and the download answers 403 no matter who asks. This
 *    is not a paywall and no account fixes it — the item simply is not a free
 *    download, and Open Library's catalogue lists it all the same, because the
 *    "ia" field means "this exists on archive.org", not "you may have it".
 *
 * So the item is asked what it actually has. One request per candidate, run a
 * few at a time, and a book whose files cannot be had is never offered — which
 * is the whole point: a shelf that only lists what it can deliver.
 */
class InternetArchiveFiles(
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 30L),
) {

    /** What an item offers. Absent when the item offers nothing readable. */
    data class Downloads(val epubUrl: String, val txtUrl: String?)

    /**
     * Resolves [identifiers] together, a few at a time.
     *
     * A catalogue page is thirty-odd books and each needs its own metadata
     * request, so doing them one after another would take longer than anyone
     * waits. Bounded rather than unbounded: thirty simultaneous requests to one
     * host is how a client gets rate-limited, which would fail the whole page.
     */
    suspend fun resolveAll(identifiers: List<String>): Map<String, Downloads> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT)
        identifiers.distinct()
            .map { id -> async { id to gate.withPermit { resolve(id) } } }
            .awaitAll()
            .mapNotNull { (id, files) -> files?.let { id to it } }
            .toMap()
    }

    /** What [identifier] offers, or null when it offers nothing downloadable. */
    suspend fun resolve(identifier: String): Downloads? {
        val json = runCatching { metadata(identifier) }.getOrNull() ?: return null
        return downloadsIn(identifier, json)
    }

    /**
     * Reads an item's metadata into the files worth offering. Separated from
     * fetching so the two decisions that actually matter — is this lent rather
     * than given, and does an EPUB exist — are testable without a network.
     */
    internal fun downloadsIn(identifier: String, json: JSONObject): Downloads? {
        if (isRestricted(json)) return null
        val files = json.optJSONArray("files") ?: return null

        var epub: String? = null
        var text: String? = null
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.optString("name")
            if (name.isBlank() || file.optLong("size", 0L) <= 0L) continue
            if (epub == null && name.endsWith(".epub", ignoreCase = true)) epub = name
            if (text == null && name.endsWith(".txt", ignoreCase = true)) text = name
        }
        val epubName = epub ?: return null
        return Downloads(
            epubUrl = downloadUrl(identifier, epubName),
            txtUrl = text?.let { downloadUrl(identifier, it) },
        )
    }

    /**
     * True when the item is lent rather than given away.
     *
     * The marker the Archive sets on a borrowable item is
     * access-restricted-item; the "inlibrary" collection is the same thing seen
     * from the other side. Either one means every download answers 403.
     */
    private fun isRestricted(json: JSONObject): Boolean {
        val metadata = json.optJSONObject("metadata") ?: return false
        if (metadata.optString("access-restricted-item").equals("true", ignoreCase = true)) return true
        val collections = metadata.opt("collection")
        val names = when (collections) {
            is String -> listOf(collections)
            is org.json.JSONArray ->
                (0 until collections.length()).map { collections.optString(it) }
            else -> emptyList()
        }
        return names.any { it.equals("inlibrary", ignoreCase = true) }
    }

    private fun metadata(identifier: String): JSONObject {
        val request = Request.Builder()
            .url("$METADATA/${identifier.trim()}")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("archive.org metadata failed (${response.code})")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun downloadUrl(identifier: String, fileName: String): String =
        "$DOWNLOAD/$identifier/" + fileName.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    private companion object {
        const val METADATA = "https://archive.org/metadata"
        const val DOWNLOAD = "https://archive.org/download"
        const val USER_AGENT = "PageTime/1.0 (Android ebook reader)"

        /** Enough to keep a page quick, few enough not to be rate-limited. */
        const val MAX_CONCURRENT = 8
    }
}

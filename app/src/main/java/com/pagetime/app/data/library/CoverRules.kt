package com.pagetime.app.data.library

import com.pagetime.app.data.recommend.ReaderPicks
import org.json.JSONObject

/**
 * The decisions behind finding a cover for a book already in the library,
 * apart from the downloading and file work, so they can be tested without a phone.
 *
 * A wrong cover is worse than none: a catalogue answers any title with
 * something, and a book wearing another book's face is hard to notice and
 * harder to trust. So every outside answer must agree with the book's own
 * title before it is used.
 */
object CoverRules {

    /** The OPF package file named by an EPUB's META-INF/container.xml, or null. */
    fun opfPath(containerXml: String): String? =
        Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1)

    /**
     * The cover image an EPUB's package file points at, as written in the file
     * (relative to the package), or null when it names none.
     *
     * Three ways a publisher says it, tried in the order they are reliable:
     * the `cover-image` property (EPUB 3), the `<meta name="cover">` pointer
     * (EPUB 2), and last an image whose name says cover.
     */
    fun coverHref(opfXml: String): String? {
        val items = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opfXml)
            .map { tag -> attributes(tag.value) }
            .toList()
        fun isImage(a: Map<String, String>) = a["media-type"].orEmpty().startsWith("image/")

        items.firstOrNull { a ->
            isImage(a) && a["properties"].orEmpty().split(' ').contains("cover-image")
        }?.get("href")?.let { return it }

        val pointer = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opfXml)
            .map { attributes(it.value) }
            .firstOrNull { it["name"].equals("cover", ignoreCase = true) }
            ?.get("content")
        if (pointer != null) {
            items.firstOrNull { it["id"] == pointer && isImage(it) }?.get("href")?.let { return it }
        }

        return items.firstOrNull { a ->
            isImage(a) && (a["id"].orEmpty().contains("cover", true) || a["href"].orEmpty().contains("cover", true))
        }?.get("href")
    }

    private fun attributes(tag: String): Map<String, String> =
        Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""").findAll(tag).associate { m ->
            m.groupValues[1].lowercase() to m.groupValues[2].ifEmpty { m.groupValues[3] }
        }

    /** A path as written in the package file, made a path inside the zip. */
    fun resolve(opfPath: String, href: String): String {
        val dir = opfPath.substringBeforeLast('/', "")
        val decoded = runCatching { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = ArrayDeque<String>()
        (if (dir.isEmpty()) decoded else "$dir/$decoded").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    /** A real video id: eleven letters, digits, dashes and underscores. */
    fun isVideoId(text: String): Boolean =
        text.length == 11 && text.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Whether a catalogue's title is this book's title, ignoring subtitles, case and punctuation. */
    fun sameTitle(book: String, found: String): Boolean {
        val a = ReaderPicks.titleKey(book)
        return a.isNotBlank() && a == ReaderPicks.titleKey(found)
    }

    /**
     * The Open Library cover for this book out of a search response: the first
     * result with a cover whose title is the book's, and whose author is the
     * book's when the book has one.
     */
    fun openLibraryCover(json: String, title: String, author: String): String? {
        val docs = runCatching { JSONObject(json).optJSONArray("docs") }.getOrNull() ?: return null
        val wanted = author.lowercase().replace(Regex("[^\\p{L} ]"), " ").trim()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val id = doc.optLong("cover_i", 0L)
            if (id <= 0 || !sameTitle(title, doc.optString("title"))) continue
            if (wanted.isNotBlank()) {
                val names = doc.optJSONArray("author_name")
                val known = (0 until (names?.length() ?: 0)).map { names!!.optString(it).lowercase() }
                val surname = wanted.split(' ').last()
                if (known.isNotEmpty() && known.none { it.contains(surname) }) continue
            }
            return "https://covers.openlibrary.org/b/id/$id-M.jpg"
        }
        return null
    }
}

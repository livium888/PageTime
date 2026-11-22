package com.pagetime.app.data.youtube

import com.pagetime.app.data.recommend.ReaderPicks

/**
 * Whether a YouTube video is already in the library as a transcript.
 *
 * A transcript book is named for its video (`yt-<videoId>`), so the same video
 * cannot be added twice: the id says it is there. Books imported before that
 * have a random id, so they are recognised by what they are: the same title
 * from the same channel.
 *
 * Pure, so the rule can be tested without a library.
 */
object VideoOwnership {

    /** What the library knows about one of its books. */
    data class OwnedBook(val id: String, val title: String, val author: String)

    fun idFor(videoId: String): String = "yt-$videoId"

    /** What the transcript fetcher writes when YouTube would not say a video's real title or channel. */
    const val FALLBACK_TITLE = "YouTube Transcript"
    const val FALLBACK_CHANNEL = "YouTube"

    /**
     * Whether a title or channel is that stand-in. Every video whose lookup
     * failed carries the same one, so it says nothing about which video it is:
     * matching on it called every later video a copy of the first, and a reader
     * adding several at once saw all but one refused as "already in your library".
     */
    fun isStandIn(text: String?): Boolean =
        text.isNullOrBlank() || titleKey(text) == titleKey(FALLBACK_TITLE) || titleKey(text) == titleKey(FALLBACK_CHANNEL)

    /**
     * A video's title for comparing, whole.
     *
     * [ReaderPicks.titleKey] stops at the first colon, which suits a book (the
     * subtitle is not its name) and ruins a lecture series: "Calculus: Lecture 1"
     * and "Calculus: Lecture 2" would both read "calculus", and the second could
     * never be added. Case, punctuation and a leading article are still ignored.
     */
    fun titleKey(title: String): String =
        title.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("^(the|a|an) "), "")

    /**
     * The video's own picture, used as the book's cover. The 16:9 size is chosen
     * because the larger default carries black bars above and below.
     */
    fun thumbnailFor(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    /** The cover for a book: its own, else the video's picture for a video book, else none. */
    fun coverFor(bookId: String, coverUrl: String?): String? =
        coverUrl ?: bookId.takeIf { it.startsWith("yt-") }
            ?.removePrefix("yt-")
            // Older transcripts were named with a random id, which names no video.
            ?.takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
            ?.let(::thumbnailFor)

    /**
     * The book that already holds this video, or null.
     *
     * [title] and [channel] are optional so a check can be made before
     * anything has been fetched, by id alone; with them it also catches a
     * transcript added before ids named their video.
     */
    fun find(
        owned: List<OwnedBook>,
        videoId: String,
        title: String? = null,
        channel: String? = null,
    ): OwnedBook? {
        owned.firstOrNull { it.id == idFor(videoId) }?.let { return it }
        if (title.isNullOrBlank()) return null
        if (isStandIn(title)) return null
        val wanted = titleKey(title)
        if (wanted.isEmpty()) return null
        return owned.firstOrNull { book ->
            book.id.startsWith("yt-") &&
                !isStandIn(book.title) &&
                titleKey(book.title) == wanted &&
                // The channel must agree when both are known, so two different
                // videos that share a common title are not taken for one.
                (isStandIn(channel) || isStandIn(book.author) || book.author.trim().equals(channel?.trim(), ignoreCase = true))
        }
    }
}

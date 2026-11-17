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
        val wanted = ReaderPicks.titleKey(title)
        if (wanted.isEmpty()) return null
        return owned.firstOrNull { book ->
            book.id.startsWith("yt-") &&
                ReaderPicks.titleKey(book.title) == wanted &&
                // The channel must agree when both are known, so two different
                // videos that share a common title are not taken for one.
                (channel.isNullOrBlank() || book.author.isBlank() || book.author.trim().equals(channel.trim(), ignoreCase = true))
        }
    }
}

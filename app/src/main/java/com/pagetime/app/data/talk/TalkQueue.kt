package com.pagetime.app.data.talk

import com.pagetime.app.data.learning.SpanPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chapters the reader has finished and could talk through, and the rules for
 * when to offer one.
 *
 * A finished chapter is always put on the list (unless the reader asked for no
 * offers at all); whether a card also appears at the moment it was finished is
 * a separate, stricter decision, so declining the card never loses the chapter.
 */
object TalkQueue {

    /** How pushy the offers are; chosen on the Talks list. */
    enum class Mode(val label: String, val hint: String) {
        AT_CHAPTER_ENDS("At chapter ends", "A quiet card when you finish a chapter, and the Talks list."),
        LIST_ONLY("Only in the list", "Nothing appears while you read; finished chapters wait in the list."),
        ONLY_ASK("Only when I ask", "No card and no list; use the reader menu."),
    }

    /** A stretch shorter than this is a few paragraphs, not a chapter to talk about. */
    const val MIN_WORDS = 800
    const val MAX_WAITING = 40
    /** Two declines in a row pause the cards for the rest of the day. */
    const val DECLINES_BEFORE_PAUSE = 2

    /** A finished chapter waiting to be talked through. */
    data class Waiting(
        val id: String,
        val bookId: String,
        val bookTitle: String,
        val label: String,
        val words: Int,
        val finishedAt: Long,
        /** Spine index for an EPUB; 0 for text. */
        val chapter: Int,
        /** Exact character offsets for text; null for an EPUB. */
        val startOffset: Int?,
        val endOffset: Int?,
    ) {
        /** The chapter's first point, starting exactly there. */
        fun startPoint() = SpanPoint(
            chapterIndex = chapter,
            fraction = 0f,
            textOffset = startOffset,
            chapterTitle = label,
            snapped = true,
        )

        /** The chapter's last point. */
        fun endPoint() = SpanPoint(
            chapterIndex = chapter,
            fraction = 1f,
            textOffset = endOffset,
            chapterTitle = label,
        )
    }

    /** Whether a finished chapter goes on the list. */
    fun shouldQueue(mode: Mode, words: Int): Boolean = mode != Mode.ONLY_ASK && words >= MIN_WORDS

    /**
     * Whether a card appears now, as well as the list entry.
     *
     * One offer a session, never on top of another prompt, and paused for the
     * day after two declines in a row.
     */
    fun shouldOfferNow(
        mode: Mode,
        words: Int,
        offeredThisSession: Boolean,
        anotherPromptShowing: Boolean,
        declinedInRow: Int,
        pausedDay: Long,
        today: Long,
    ): Boolean =
        mode == Mode.AT_CHAPTER_ENDS &&
            words >= MIN_WORDS &&
            !offeredThisSession &&
            !anotherPromptShowing &&
            pausedDay != today

    /** What happens when a card is declined: the count, and the day to pause on (or the old one). */
    fun afterDecline(declinedInRow: Int, pausedDay: Long, today: Long): Pair<Int, Long> {
        val next = declinedInRow + 1
        return if (next >= DECLINES_BEFORE_PAUSE) 0 to today else next to pausedDay
    }

    fun idFor(bookId: String, chapter: Int, startOffset: Int?): String = "$bookId#$chapter#${startOffset ?: 0}"

    // Storage, as one JSON string.

    fun encode(list: List<Waiting>): String = JSONArray().also { array ->
        list.forEach { w ->
            array.put(
                JSONObject().put("i", w.id).put("b", w.bookId).put("t", w.bookTitle).put("l", w.label)
                    .put("w", w.words).put("f", w.finishedAt).put("c", w.chapter)
                    .put("s", w.startOffset ?: -1).put("e", w.endOffset ?: -1)
            )
        }
    }.toString()

    fun decode(json: String?): List<Waiting> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val s = o.optInt("s", -1)
                val e = o.optInt("e", -1)
                Waiting(
                    id = o.optString("i"), bookId = o.optString("b"), bookTitle = o.optString("t"),
                    label = o.optString("l"), words = o.optInt("w"), finishedAt = o.optLong("f"),
                    chapter = o.optInt("c"), startOffset = s.takeIf { it >= 0 }, endOffset = e.takeIf { it >= 0 },
                ).takeIf { it.id.isNotBlank() && it.bookId.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Adds one, newest first, once per id, keeping the list bounded. */
    fun add(list: List<Waiting>, new: Waiting): List<Waiting> =
        (listOf(new) + list.filter { it.id != new.id }).take(MAX_WAITING)

    fun remove(list: List<Waiting>, id: String): List<Waiting> = list.filter { it.id != id }

    /** Entries for a book that is no longer there are dropped. */
    fun forBooks(list: List<Waiting>, bookIds: Set<String>): List<Waiting> = list.filter { it.bookId in bookIds }
}

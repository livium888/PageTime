package com.pagetime.app.data.learning

import org.json.JSONObject

/**
 * A place in a book, precise enough to cut text at.
 *
 * Two shapes, because the two kinds of reader know different things. An EPUB
 * reports a chapter and how far through it the page begins — a fraction of
 * Readium's own rendering, not of the text, so it lands near the right
 * paragraph rather than on it. A plain-text book reports exact character
 * offsets, because its pages are laid out from the text itself.
 */
data class SpanPoint(
    /** Spine index for an EPUB; zero for plain text. */
    val chapterIndex: Int,
    /** 0..1 through that chapter (EPUB) or the whole text (plain text). */
    val fraction: Float,
    /** Exact character offset, when the reader knows one. Plain text only. */
    val textOffset: Int? = null,
    /** For the sheet's "from… to…" line; never used to cut text. */
    val chapterTitle: String? = null,
    /**
     * This point is already a clean boundary, so a span starting here begins
     * exactly here instead of backing up to the start of the paragraph.
     *
     * Set on the point a finished span hands to the next one. Without it the
     * next span would snap back to the start of the paragraph the last one
     * ended in, and that paragraph — and the cards made from it — would be in
     * both.
     */
    val snapped: Boolean = false,
) {
    /**
     * Orders points within one book. Within a chapter, exact offsets compare
     * when both points have one; otherwise the fractions do.
     */
    fun isBefore(other: SpanPoint): Boolean {
        if (chapterIndex != other.chapterIndex) return chapterIndex < other.chapterIndex
        val mine = textOffset
        val theirs = other.textOffset
        return if (mine != null && theirs != null) mine < theirs else fraction < other.fraction
    }

    fun toJson(): JSONObject = JSONObject()
        .put("c", chapterIndex)
        .put("f", fraction.toDouble())
        .apply {
            textOffset?.let { put("o", it) }
            chapterTitle?.let { put("t", it) }
            if (snapped) put("s", true)
        }

    companion object {
        fun fromJson(obj: JSONObject): SpanPoint = SpanPoint(
            chapterIndex = obj.optInt("c", 0),
            fraction = obj.optDouble("f", 0.0).toFloat().coerceIn(0f, 1f),
            textOffset = if (obj.has("o")) obj.optInt("o") else null,
            chapterTitle = obj.optString("t").takeIf { it.isNotBlank() },
            snapped = obj.optBoolean("s", false),
        )
    }
}

/**
 * The stretch of a book between two reading positions, as text.
 *
 * WHAT "END OF THE PAGE" MEANS HERE
 *
 * The text is cut at paragraph boundaries, not at the exact position. The
 * position is only ever approximate for an EPUB, and a card made from half a
 * paragraph is a card about half a thought. So the span starts at the start of
 * the paragraph the marker is in and ends at the end of the paragraph the
 * current page begins in. [slice] can then be nudged a paragraph at a time
 * ([endAdjust]), because "close" is the best an approximate position can give
 * and the reader can see the last words and correct them.
 *
 * Pure functions over text, so the cutting rules can be tested without a
 * phone, an EPUB or a reader.
 */
object ReadingSpan {

    /** Over this the span is a warning: a model covers a long text unevenly. */
    const val WARN_WORDS = 5_000

    /** Over this the span cannot be copied; the reader moves the start closer. */
    const val MAX_WORDS = 15_000

    /** Below this there is nothing worth a card yet. */
    const val MIN_WORDS = 40

    data class Slice(
        val text: String,
        val words: Int,
        val firstWords: String,
        val lastWords: String,
        /** The current position is before the start, so there is no span. */
        val backwards: Boolean = false,
        /**
         * Exactly where the NEXT span should start: just past the last text
         * this one included. Null for an empty span.
         */
        val next: SpanPoint? = null,
    ) {
        val isEmpty: Boolean get() = words == 0
        val tooShort: Boolean get() = words < MIN_WORDS
        val long: Boolean get() = words > WARN_WORDS
        val tooLong: Boolean get() = words > MAX_WORDS
    }

    private const val PARAGRAPH = "\n\n"
    private val SENTENCE_END = Regex("""[.!?]["')\]”’]*(\s|$)""")

    fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun edgeWords(text: String, n: Int, fromEnd: Boolean): String {
        val all = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (all.isEmpty()) return ""
        return if (fromEnd) {
            (if (all.size > n) "… " else "") + all.takeLast(n).joinToString(" ")
        } else {
            all.take(n).joinToString(" ") + (if (all.size > n) " …" else "")
        }
    }

    private fun sliceOf(text: String, backwards: Boolean = false, next: SpanPoint? = null): Slice {
        val trimmed = text.trim()
        return Slice(
            text = trimmed,
            words = words(trimmed),
            firstWords = edgeWords(trimmed, EDGE_WORDS, fromEnd = false),
            lastWords = edgeWords(trimmed, EDGE_WORDS, fromEnd = true),
            backwards = backwards,
            next = next.takeIf { trimmed.isNotEmpty() },
        )
    }

    private const val EDGE_WORDS = 8

    // region EPUB: paragraphs separated by a blank line, a chapter at a time

    /**
     * The text from [start] to [end] across however many chapters lie between.
     *
     * [chapterText] reads one chapter (paragraphs blank-line separated); it is
     * a function so a long book is read a chapter at a time and only the
     * chapters in the span are read at all.
     */
    fun slice(
        chapterText: (Int) -> String,
        chapterCount: Int,
        start: SpanPoint,
        end: SpanPoint,
        endAdjust: Int = 0,
    ): Slice {
        if (chapterCount <= 0) return sliceOf("")
        if (end.isBefore(start)) return sliceOf("", backwards = true)
        val first = start.chapterIndex.coerceIn(0, chapterCount - 1)
        val last = end.chapterIndex.coerceIn(first, chapterCount - 1)

        val parts = mutableListOf<String>()
        var next: SpanPoint? = null
        for (index in first..last) {
            val raw = breakUpLongParagraphs(chapterText(index))
            if (raw.isBlank()) continue
            val from = when {
                index != first -> 0
                // A boundary handed over by the previous span: start exactly there.
                start.snapped -> positionIn(raw, start.fraction)
                else -> paragraphStart(raw, positionIn(raw, start.fraction))
            }
            val to = if (index == last) {
                adjustEnd(raw, paragraphEnd(raw, positionIn(raw, end.fraction)), from, endAdjust)
            } else {
                raw.length
            }
            if (to > from) {
                parts += raw.substring(from, to).trim()
                // One character into the blank line after the last paragraph,
                // so a fractional position that rounds either way still lands
                // on the gap or the next paragraph's first letter, never inside
                // a word and never back in the paragraph just taken.
                next = SpanPoint(
                    chapterIndex = index,
                    fraction = ((to + 1).toFloat() / raw.length).coerceIn(0f, 1f),
                    snapped = true,
                )
            }
        }
        return sliceOf(parts.joinToString(PARAGRAPH), next = next)
    }

    /** A paragraph longer than this is treated as unbroken markup, not as one paragraph. */
    internal const val LONG_PARAGRAPH_WORDS = 250

    /** Where an over-long paragraph is cut: the first sentence end after this many words. */
    private const val CHUNK_WORDS = 120

    /**
     * Gives text with no real paragraph breaks some.
     *
     * Some EPUBs (typically ones converted from PDF) put a whole chapter in one
     * element. Snapping to "the paragraph" then snaps to the chapter's first
     * and last word, and three pages read becomes twenty thousand words. Any
     * paragraph over [LONG_PARAGRAPH_WORDS] is cut into chunks of about
     * [CHUNK_WORDS] words at sentence ends (or at a word, if there is no
     * punctuation). Deterministic, so a position taken on the result stays
     * valid on a later call.
     */
    internal fun breakUpLongParagraphs(raw: String): String {
        if (raw.isBlank()) return raw
        return raw.split(PARAGRAPH).joinToString(PARAGRAPH) { para ->
            if (words(para) <= LONG_PARAGRAPH_WORDS) para else chunk(para)
        }
    }

    private fun chunk(para: String): String {
        val out = StringBuilder()
        var count = 0
        var i = 0
        val n = para.length
        while (i < n) {
            val start = i
            while (i < n && !para[i].isWhitespace()) i++
            val word = para.substring(start, i)
            while (i < n && para[i].isWhitespace()) i++
            out.append(word)
            count++
            val sentenceEnd = word.isNotEmpty() && word.last() in SENTENCE_ENDERS &&
                word.any { it == '.' || it == '!' || it == '?' }
            val cut = i < n && (count >= CHUNK_WORDS * 2 || (count >= CHUNK_WORDS && sentenceEnd))
            if (cut) { out.append(PARAGRAPH); count = 0 } else if (i < n) out.append(' ')
        }
        return out.toString()
    }

    private fun positionIn(raw: String, fraction: Float): Int =
        (raw.length * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, raw.length)

    /** The start of the paragraph containing [index]. */
    internal fun paragraphStart(raw: String, index: Int): Int {
        if (index <= 0) return 0
        val boundary = raw.lastIndexOf(PARAGRAPH, startIndex = (index - 1).coerceAtLeast(0))
        return if (boundary < 0) 0 else boundary + PARAGRAPH.length
    }

    /** The end of the paragraph containing [index] (exclusive). */
    internal fun paragraphEnd(raw: String, index: Int): Int {
        val boundary = raw.indexOf(PARAGRAPH, startIndex = index.coerceIn(0, raw.length))
        return if (boundary < 0) raw.length else boundary
    }

    /**
     * Moves the end by whole paragraphs, never to or before the start.
     *
     * Positive adds paragraphs the reader has probably read; negative drops
     * ones they have probably not reached. Stops at the chapter's ends rather
     * than wrapping, and always leaves at least one character of span.
     */
    internal fun adjustEnd(raw: String, end: Int, from: Int, steps: Int): Int {
        var e = end
        var remaining = steps
        while (remaining > 0 && e < raw.length) {
            val next = raw.indexOf(PARAGRAPH, startIndex = e + PARAGRAPH.length)
            e = if (next < 0) raw.length else next
            remaining--
        }
        while (remaining < 0) {
            val previous = raw.lastIndexOf(PARAGRAPH, startIndex = (e - 1).coerceAtLeast(0))
            if (previous <= from) break
            e = previous
            remaining++
        }
        return e
    }

    // endregion

    // region Plain text: exact offsets

    /**
     * The text between two exact offsets of one long text.
     *
     * The start is pulled back to the start of its paragraph (or sentence) and
     * the end pushed forward to the end of its sentence, so a page that breaks
     * mid-sentence does not leave a card about half of one. Plain text has no
     * reliable paragraph structure — a transcript can be one block — so the
     * fallback is a sentence boundary, then the offset itself.
     */
    fun slicePlain(full: String, start: SpanPoint, end: SpanPoint, endAdjust: Int = 0): Slice {
        if (full.isBlank()) return sliceOf("")
        val s = (start.textOffset ?: positionIn(full, start.fraction)).coerceIn(0, full.length)
        val e = (end.textOffset ?: positionIn(full, end.fraction)).coerceIn(0, full.length)
        if (e < s) return sliceOf("", backwards = true)
        val from = if (start.snapped) s else plainStart(full, s)
        var to = plainEnd(full, e)
        if (endAdjust != 0) to = adjustEnd(full, to, from, endAdjust)
        to = to.coerceAtLeast(from)
        return sliceOf(
            full.substring(from, to),
            next = SpanPoint(
                chapterIndex = 0,
                fraction = to.toFloat() / full.length,
                textOffset = to,
                snapped = true,
            ),
        )
    }

    private const val SENTENCE_ENDERS = ".!?\"')]”’"

    private const val LOOKBACK = 3_000
    private const val LOOKAHEAD = 600

    internal fun plainStart(full: String, offset: Int): Int {
        if (offset <= 0) return 0
        val windowStart = (offset - LOOKBACK).coerceAtLeast(0)
        val window = full.substring(windowStart, offset)
        val paragraph = window.lastIndexOf(PARAGRAPH)
        if (paragraph >= 0) return windowStart + paragraph + PARAGRAPH.length
        // Mid-sentence: back up to just after the previous sentence end.
        val tail = window.takeLast(LOOKAHEAD)
        val tailStart = window.length - tail.length
        val last = SENTENCE_END.findAll(tail).lastOrNull()
        if (last != null) return windowStart + tailStart + last.range.last + 1
        return offset
    }

    internal fun plainEnd(full: String, offset: Int): Int {
        if (offset >= full.length) return full.length
        // Already at the end of a sentence: the page broke cleanly, and pushing
        // on would add a sentence the reader has not necessarily seen.
        var i = offset - 1
        while (i >= 0 && full[i].isWhitespace()) i--
        if (i >= 0 && full[i] in SENTENCE_ENDERS) return offset
        val window = full.substring(offset, (offset + LOOKAHEAD).coerceAtMost(full.length))
        val paragraph = window.indexOf(PARAGRAPH)
        val sentence = SENTENCE_END.find(window)?.range?.last?.plus(1)
        val cut = listOfNotNull(sentence, paragraph.takeIf { it >= 0 }).minOrNull()
        return if (cut != null) offset + cut else offset
    }

    // endregion
}

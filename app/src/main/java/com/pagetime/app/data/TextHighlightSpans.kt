package com.pagetime.app.data

import com.pagetime.app.data.local.TextHighlightEntity

/**
 * The pure geometry of text highlights.
 *
 * Kept out of the database and the screen so the awkward parts — what happens
 * when a highlight crosses a page boundary, whether two highlights overlap —
 * can be tested without a phone. Same split the rest of the app uses for its
 * rules.
 *
 * Plain-text highlights are stored as whole-book character offsets. A page is
 * just a window onto those offsets ([TextPage] knows its own start and end),
 * so a highlight that spans several pages renders as background on each page
 * it touches, with the first and last pages clipped to the span.
 */
object TextHighlightSpans {

    /** Longest quote stored for a txt span; beyond this, the span still renders. */
    const val QUOTE_CAP_CHARS = 2_000

    /** Whether a highlight is a plain-text span (as opposed to an EPUB locator). */
    fun isTxt(highlight: TextHighlightEntity): Boolean = highlight.kind == "txt"

    /**
     * The highlight's whole-book range, or null when the row is malformed.
     */
    fun txtRange(highlight: TextHighlightEntity): IntRange? {
        if (!isTxt(highlight)) return null
        val start = highlight.startOffset
        val end = highlight.endOffset
        if (start < 0 || end <= start) return null
        return start until end
    }

    /**
     * The highlight ranges that touch one page, clipped to it and page-relative.
     *
     * [pageStartOffset]..[pageEndOffset] is the page's window onto the whole
     * text. Returns empty when nothing overlaps. Ranges are sorted and merged,
     * so a caller can lay background down in one left-to-right pass.
     */
    fun txtPageRanges(
        highlights: List<TextHighlightEntity>,
        pageStartOffset: Int,
        pageEndOffset: Int
    ): List<Pair<Int, Int>> {
        if (highlights.isEmpty() || pageEndOffset <= pageStartOffset) return emptyList()
        val clipped = highlights
            .mapNotNull { txtRange(it) }
            .mapNotNull { range ->
                // Page-relative, because the caller indexes into the page's own
                // substring: the page window is subtracted back out here.
                val start = maxOf(range.first, pageStartOffset) - pageStartOffset
                val end = minOf(range.last + 1, pageEndOffset) - pageStartOffset
                if (end > start) start to end else null
            }
        return mergeRanges(clipped)
    }

    /**
     * Sorts and merges overlapping or adjacent ranges.
     *
     * Overlaps cannot happen from the anchor flow (a new highlight starts at
     * the end of the previous page), but two highlights can share a page, and
     * an overlap must not double-paint or double-count.
     */
    fun mergeRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        for ((start, end) in sorted.drop(1)) {
            val last = merged.last()
            if (start <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, end)
            } else {
                merged += start to end
            }
        }
        return merged
    }

    /** The highlighted text for a txt span, capped. */
    fun quoteForTxt(text: String, start: Int, end: Int): String {
        if (text.isEmpty() || end <= start) return ""
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        return text.substring(safeStart, safeEnd).trim()
            .take(QUOTE_CAP_CHARS)
    }
}
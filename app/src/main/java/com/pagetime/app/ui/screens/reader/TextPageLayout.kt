package com.pagetime.app.ui.screens.reader

/** A stable, precomputed page of plain-text content. */
data class TextPage(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String
)

/**
 * Paginates plain text by ACTUAL rendered size, never by character counts.
 *
 * The previous implementation guessed "characters per page" from typography
 * heuristics and a hard-coded 320dp screen assumption. On small phones that
 * overflowed the screen (readers lost the ends of pages), and on large screens
 * it wasted most of the page. This object now works from real layout: the
 * caller supplies the pixel height of the on-screen page area and a function
 * that measures the rendered height of a text snippet at the reader's exact
 * typography (font family, size, line height, scale, width). A page is
 * whatever actually fits that space on THIS device.
 *
 * Boundaries still prefer paragraph → newline → space breaks near the fit
 * limit, so page turns feel book-like and reopening never shifts content.
 */
object TextPageLayout {

    /**
     * Splits [content] into pages whose measured height fits [maxHeightPx].
     *
     * @param measureHeightPx must return the rendered height of the exact
     *   string it receives, laid out with the same style the reader draws with.
     */
    fun paginateMeasured(
        content: String,
        maxHeightPx: Int,
        measureHeightPx: (String) -> Int
    ): List<TextPage> {
        if (content.isEmpty()) return listOf(TextPage(0, 0, 0, ""))
        require(maxHeightPx > 0) { "Page height must be positive" }

        val pages = mutableListOf<TextPage>()
        var start = 0
        while (start < content.length) {
            while (start < content.length && content[start].isWhitespace()) start++
            if (start >= content.length) break

            // Binary search the largest end offset whose rendered page fits.
            var lo = start + 1            // one character is assumed to fit
            var hi = content.length       // the whole remainder, may overflow
            if (measureHeightPx(trimmed(content, start, content.length)) <= maxHeightPx) {
                lo = content.length
            } else {
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (measureHeightPx(trimmed(content, start, mid)) <= maxHeightPx) {
                        lo = mid
                    } else {
                        hi = mid
                    }
                }
            }
            val end = snapToBoundary(content, start, lo, maxHeightPx, measureHeightPx)
            pages += TextPage(
                index = pages.size,
                startOffset = start,
                endOffset = end,
                text = trimmed(content, start, end)
            )
            start = end
        }
        return pages
    }

    private fun trimmed(content: String, start: Int, end: Int): String =
        content.substring(start, end).trim()

    /**
     * Walks back from the hard fit limit to a paragraph, newline, or space
     * boundary — but keeps the hard cut if the nicer boundary would overflow
     * the page. Guarantees forward progress for pathological single-word text.
     */
    private fun snapToBoundary(
        content: String,
        start: Int,
        rawEnd: Int,
        maxHeightPx: Int,
        measureHeightPx: (String) -> Int
    ): Int {
        if (rawEnd >= content.length) return content.length
        val windowStart = (rawEnd - 220).coerceAtLeast(start + 1)
        val window = content.substring(windowStart, rawEnd)
        val candidates = buildList {
            add(window.lastIndexOf("\n\n"))
            add(window.lastIndexOf('\n'))
            add(window.lastIndexOf(' '))
        }
            .filter { it >= 0 }
            .map { windowStart + it + 1 }
            .filter { it > start }
            .sortedDescending()
        for (candidate in candidates) {
            if (measureHeightPx(trimmed(content, start, candidate)) <= maxHeightPx) {
                return candidate
            }
        }
        // rawEnd > start always, so the loop above always advances.
        return rawEnd
    }

    // ── Legacy character-based fallback ─────────────────────────────────────
    // Kept only as a safety net if device measurement ever fails; the reader
    // must always show the whole book rather than a blank screen.

    private const val FALLBACK_TARGET_CHARS_PER_PAGE = 900
    private const val FALLBACK_MIN_CHARS_PER_PAGE = 600

    fun targetCharsFor(settings: com.pagetime.app.data.local.ReaderSettings): Int {
        val fontFactor = 16f / settings.fontSizeSp.coerceIn(12f, 32f)
        val lineFactor = 1.5f / settings.lineHeight.coerceIn(1.0f, 2.2f)
        val marginFactor = (320f - settings.marginDp.coerceIn(8f, 80f)) / 320f
        return (FALLBACK_TARGET_CHARS_PER_PAGE * fontFactor * lineFactor * marginFactor)
            .toInt()
            .coerceIn(400, 3_000)
    }

    fun paginate(content: String, targetChars: Int = FALLBACK_TARGET_CHARS_PER_PAGE): List<TextPage> {
        if (content.isEmpty()) return listOf(TextPage(0, 0, 0, ""))
        val pages = mutableListOf<TextPage>()
        var start = 0
        var pageIndex = 0

        while (start < content.length) {
            val preferredEnd = (start + targetChars).coerceAtMost(content.length)
            val end = if (preferredEnd == content.length) {
                content.length
            } else {
                findBoundary(content, start, preferredEnd, targetChars)
            }
            val safeEnd = end.coerceIn((start + 1).coerceAtMost(content.length), content.length)
            pages += TextPage(
                index = pageIndex++,
                startOffset = start,
                endOffset = safeEnd,
                text = content.substring(start, safeEnd).trim()
            )
            start = safeEnd
            while (start < content.length && content[start].isWhitespace()) start++
        }
        return pages
    }

    fun pageForFraction(pages: List<TextPage>, fraction: Float): Int {
        if (pages.isEmpty()) return 0
        return ((fraction.coerceIn(0f, 0.99999f) * pages.size).toInt())
            .coerceIn(0, pages.lastIndex)
    }

    fun pageForOffset(pages: List<TextPage>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        val target = offset.coerceAtLeast(0)
        return pages.indexOfLast { it.startOffset <= target }.coerceIn(0, pages.lastIndex)
    }

    fun fractionForPage(pageIndex: Int, pageCount: Int): Float {
        if (pageCount <= 1) return if (pageIndex > 0) 1f else 0f
        return (pageIndex.coerceIn(0, pageCount - 1).toFloat() / (pageCount - 1))
            .coerceIn(0f, 1f)
    }

    private fun findBoundary(content: String, start: Int, preferredEnd: Int, targetChars: Int): Int {
        val minimum = (start + (FALLBACK_MIN_CHARS_PER_PAGE.coerceAtMost(targetChars))).coerceAtMost(preferredEnd)
        val paragraph = content.lastIndexOf("\n\n", preferredEnd)
        if (paragraph >= minimum) return paragraph + 2
        val newline = content.lastIndexOf('\n', preferredEnd)
        if (newline >= minimum) return newline + 1
        val space = content.lastIndexOf(' ', preferredEnd)
        if (space >= minimum) return space + 1
        return preferredEnd
    }
}

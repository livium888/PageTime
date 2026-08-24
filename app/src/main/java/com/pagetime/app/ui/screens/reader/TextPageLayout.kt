package com.pagetime.app.ui.screens.reader

/** A stable, precomputed page of plain-text content. */
data class TextPage(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String
)

/**
 * Splits text into readable, deterministic pages without depending on Compose
 * measurement. Page boundaries prefer paragraph and word boundaries, so reopening
 * a book does not cause content to jump while layout is still settling.
 */
object TextPageLayout {
    private const val TARGET_CHARS_PER_PAGE = 2_400
    private const val MIN_CHARS_PER_PAGE = 1_600

    /**
     * Page size adapts to the active typography: bigger text or looser spacing
     * means fewer characters per page, so changing the font visibly re-flows the
     * book instead of clipping it.
     */
    fun targetCharsFor(settings: com.pagetime.app.data.local.ReaderSettings): Int {
        val fontFactor = 16f / settings.fontSizeSp.coerceIn(12f, 32f)
        val lineFactor = 1.5f / settings.lineHeight.coerceIn(1.0f, 2.2f)
        return (TARGET_CHARS_PER_PAGE * fontFactor * lineFactor)
            .toInt()
            .coerceIn(900, 4_200)
    }

    fun paginate(content: String, targetChars: Int = TARGET_CHARS_PER_PAGE): List<TextPage> {
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
        val minimum = (start + (MIN_CHARS_PER_PAGE.coerceAtMost(targetChars))).coerceAtMost(preferredEnd)
        val paragraph = content.lastIndexOf("\n\n", preferredEnd)
        if (paragraph >= minimum) return paragraph + 2
        val newline = content.lastIndexOf('\n', preferredEnd)
        if (newline >= minimum) return newline + 1
        val space = content.lastIndexOf(' ', preferredEnd)
        if (space >= minimum) return space + 1
        return preferredEnd
    }
}

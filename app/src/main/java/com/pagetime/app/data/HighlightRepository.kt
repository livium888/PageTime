package com.pagetime.app.data

import com.pagetime.app.data.local.TextHighlightDao
import com.pagetime.app.data.local.TextHighlightEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Persistent text highlights for both book formats.
 *
 * Plain-text highlights are whole-page spans captured by anchoring ("start
 * here" / "end here"): character offsets in the book text, so a span can
 * stretch across any number of pages with no selection machinery in a paged
 * reader. EPUB highlights are native Readium selections — the Locator's
 * offsets already cover the selected range, which inside one resource can span
 * several rendered pages.
 */
class HighlightRepository(
    private val dao: TextHighlightDao
) {

    fun observeForBook(bookId: String): Flow<List<TextHighlightEntity>> = dao.observeForBook(bookId)

    suspend fun get(id: String): TextHighlightEntity? = dao.get(id)

    /**
     * Marks a plain-text span, [startOffset]..[endOffset] in the whole text.
     *
     * Normalized and clipped here rather than trusting callers: a reversed or
     * empty span would render nothing but would still occupy a row forever.
     */
    suspend fun createTxtSpan(
        bookId: String,
        startOffset: Int,
        endOffset: Int,
        text: String
    ): TextHighlightEntity? {
        if (startOffset < 0 || endOffset <= startOffset) return null
        val highlight = TextHighlightEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            kind = "txt",
            startOffset = startOffset,
            endOffset = endOffset,
            quote = TextHighlightSpans.quoteForTxt(text, startOffset, endOffset),
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(highlight)
        return highlight
    }

    /**
     * Marks the current Readium selection as a highlight.
     *
     * [locatorJson] is the selection Locator from the navigator; its offsets
     * span the selected range. [text] is the highlighted text itself.
     */
    suspend fun createEpubSpan(bookId: String, locatorJson: String, text: String): TextHighlightEntity? {
        val trimmed = text.trim()
        if (locatorJson.isBlank() || trimmed.isBlank()) return null
        val highlight = TextHighlightEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            kind = "epub",
            startLocatorJson = locatorJson,
            quote = trimmed.take(TextHighlightSpans.QUOTE_CAP_CHARS),
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(highlight)
        return highlight
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }
}
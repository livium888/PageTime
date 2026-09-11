package com.pagetime.app.data

import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.PendingReaderSource
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.local.TextHighlightDao
import com.pagetime.app.data.local.TextHighlightEntity
import java.io.File
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
 *
 * Like [PagemarkRepository], this owns more than its own rows: opening a
 * highlight is a two-part move (aim the reader, then let the caller open the
 * book), and the aim has to be written before the reader loads or it restores
 * the old position instead.
 */
class HighlightRepository(
    private val dao: TextHighlightDao,
    private val settingsRepository: SettingsRepository,
    private val bookDao: BookDao
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

    /**
     * Aims the reader at this highlight so it can be opened from the list.
     *
     * The same mechanism the reading queue uses to open a chunk: write the
     * pending source, then open the book. The reader consumes it while loading,
     * so it cannot restore a stale position first and then jump.
     *
     * The two anchors need different things. An EPUB highlight stores a Locator,
     * which is a position outright. A plain-text highlight stores character
     * offsets, so the offset is turned back into the fraction the paged reader
     * restores from.
     */
    suspend fun aimAt(highlight: TextHighlightEntity) {
        val source = if (highlight.kind == "epub") {
            highlight.startLocatorJson
                ?.takeIf { it.isNotBlank() }
                ?.let { PendingReaderSource(locatorJson = it, fraction = null) }
        } else {
            txtFraction(highlight)?.let { PendingReaderSource(locatorJson = null, fraction = it) }
        }
        if (source != null) settingsRepository.setPendingReaderSource(highlight.bookId, source)
    }

    private suspend fun txtFraction(highlight: TextHighlightEntity): Float? {
        if (highlight.startOffset < 0) return null
        val length = textLength(highlight.bookId) ?: return null
        return (highlight.startOffset.toFloat() / length.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Total characters in a plain-text book, or null when the file cannot be
     * read.
     *
     * A highlight's stored offsets only become a position when divided by this,
     * which is what both "open this highlight" and the percentage in the list
     * need. One read serves the whole list rather than one read per row.
     */
    suspend fun textLength(bookId: String): Int? {
        val book = bookDao.getById(bookId) ?: return null
        return runCatching { File(book.localPath).readText().length }.getOrNull()?.takeIf { it > 0 }
    }
}

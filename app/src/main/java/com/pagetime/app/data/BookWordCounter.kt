package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContextExtractor
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.isReadiumBook
import java.io.File

/**
 * A book's total word count, computed once and cached forever after — see
 * [com.pagetime.app.ui.screens.reader.ReadingPace] for why
 * [com.pagetime.app.ui.screens.reader.ReadingGuard] wants this: a book's own
 * fraction is not a safe unit for reading pace, since a smaller font (or
 * simply a shorter book) packs a different number of words into the same
 * fraction. Words are the one unit that stays put regardless of font size
 * or pagination.
 *
 * Unlike [BookGenreClassifier], not throttled or run ahead of need: counting
 * words means walking every chapter's real text, which for an EPUB means
 * unzipping and parsing it — worth paying only for a book someone is
 * actually reading, not the whole library on every Library-screen visit.
 */
class BookWordCounter(
    private val bookDao: BookDao,
    private val contextExtractor: LearningContextExtractor,
) {
    /**
     * The book's word count, computing and caching it first if this is the
     * first time — null only if counting genuinely failed (an unparseable
     * file), in which case [com.pagetime.app.ui.screens.reader.ReadingGuard]
     * simply falls back to its cruder book-fraction pace check, same as
     * before this existed.
     */
    suspend fun wordCount(book: BookEntity): Int? {
        book.wordCount?.let { return it }
        val counted = runCatching { compute(book) }.getOrNull()?.takeIf { it > 0 } ?: return null
        runCatching { bookDao.updateWordCount(book.id, counted) }
        return counted
    }

    private suspend fun compute(book: BookEntity): Int {
        if (!book.isReadiumBook) {
            return BookWordCount.count(File(book.localPath).readText())
        }
        val chapters = contextExtractor.chapterCount(book)
        var total = 0
        for (index in 0 until chapters) {
            total += BookWordCount.count(contextExtractor.chapterText(book, index))
        }
        return total
    }
}

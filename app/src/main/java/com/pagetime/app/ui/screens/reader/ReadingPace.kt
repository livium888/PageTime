package com.pagetime.app.ui.screens.reader

/**
 * Whether a burst of forward reading progress is faster than any human
 * plausibly reads — [ReadingGuard]'s pace defense, pulled out pure so the
 * math can be tested directly against real numbers instead of only through
 * a running guard.
 *
 * The naive version of this check — book-fraction covered per minute against
 * a fixed ceiling — breaks the moment two books, or the same book at two
 * font sizes, pack a different number of words into that fraction. A smaller
 * font fits more words per screen, so turning pages at a completely normal
 * pace covers a BIGGER fraction of the book per minute purely because
 * there's more text per page — not because anyone read faster. The same
 * failure hits any short document (an imported article, a YouTube
 * transcript) even at default font size, since "fraction of the whole
 * thing" shrinks the shorter the thing is. KOReader's own community hit
 * this exact wall building reading statistics and moved off "pages" onto
 * words for the same reason — see
 * https://github.com/koreader/koreader/issues/4865 ("the definition of a
 * page is unclear... if you increase the font size... your page count will
 * increase too, because now less text fits on a page").
 *
 * Falls back to the old fraction-based ceiling only when a book's word
 * count isn't known yet (freshly imported, or word-counting failed) — never
 * blocks crediting outright for lack of that number.
 */
object ReadingPace {
    fun isTooFast(
        forwardProgressDelta: Float,
        minutesElapsed: Float,
        totalWords: Int?,
        maxFractionPerMinute: Float,
        maxWordsPerMinute: Float,
    ): Boolean {
        if (minutesElapsed <= 0f) return false
        return if (totalWords != null && totalWords > 0) {
            val wordsPerMinute = (forwardProgressDelta * totalWords) / minutesElapsed
            wordsPerMinute > maxWordsPerMinute
        } else {
            forwardProgressDelta / minutesElapsed > maxFractionPerMinute
        }
    }
}

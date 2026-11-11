package com.pagetime.app.data.learning

/**
 * When to suggest making cards, so the reader does not have to remember to.
 *
 * THE COUNT ARMS IT, A BREAK SHOWS IT
 *
 * The words read since the last batch of cards decide that a suggestion is
 * owed. They do not decide the moment. Making cards means leaving the book for
 * a minute or two, and an offer of that in the middle of a sentence is one the
 * reader learns to dismiss without reading; the same offer between two thoughts
 * costs nothing. So once enough has been read the suggestion waits for a page
 * that starts on a heading or a new paragraph, or a new chapter, and only if
 * none comes within a few pages does it stop waiting.
 *
 * It is a bar at the foot of the page and not a dialog. Reading on is an
 * answer: the bar leaves by itself and that counts as "later".
 *
 * "LATER" KEEPS COUNTING
 *
 * Declining does not reset anything. The count carries on from where it was and
 * the next suggestion comes after the same number of words again, showing the
 * new total. Only making the cards starts the count over, because that is what
 * moves the start of the stretch.
 *
 * Pure rules, so they can be tested without a reader.
 */
object CardNudge {

    /** Words between suggestions: about six minutes of reading. */
    const val DEFAULT_EVERY = 1_500

    /** What the reader can choose between in Settings. */
    val EVERY_CHOICES = listOf(1_000, 1_500, 2_500)

    /** Page turns a suggestion waits for a natural break before appearing anyway. */
    const val BREAK_PATIENCE = 3

    /** Page turns the bar stays through before reading on is taken as "later". */
    const val LINGER_TURNS = 2

    data class Offer(
        /** Words read since the last cards were made. */
        val words: Int,
        /** About how many cards that is worth, at the reader's density. */
        val cards: Int,
        /** So much has piled up that an AI will start covering it unevenly. */
        val long: Boolean,
    ) {
        val headline: String
            get() = "${grouped(words)} words read · about $cards ${if (cards == 1) "card" else "cards"}"

        val detail: String?
            get() = if (long) "That is a lot for one batch. An AI covers a long stretch unevenly." else null
    }

    fun everyOrDefault(value: Int?): Int = value?.takeIf { it in EVERY_CHOICES } ?: DEFAULT_EVERY

    /**
     * The count at which the reader last said "later", if it still applies.
     *
     * After cards are made the stretch starts again and is shorter than the
     * count that was declined; that decline belongs to the old stretch.
     */
    fun laterStillApplies(words: Int, laterAt: Int): Int = if (words < laterAt) 0 else laterAt.coerceAtLeast(0)

    /** The count at which the next suggestion is owed. */
    fun nextAskAt(words: Int, laterAt: Int, every: Int): Int =
        laterStillApplies(words, laterAt) + every.coerceAtLeast(1)

    fun due(words: Int, laterAt: Int, every: Int): Boolean = words >= nextAskAt(words, laterAt, every)

    fun offer(words: Int, density: CardDensity): Offer = Offer(
        words = words,
        cards = density.target(words),
        long = words >= ReadingSpan.WARN_WORDS,
    )

    /** Whether an owed suggestion appears on this page. */
    fun showNow(atBreak: Boolean, turnsWaited: Int): Boolean = atBreak || turnsWaited >= BREAK_PATIENCE

    /**
     * Whether a page of a text book starts between two thoughts: at the very
     * start, on a heading, or on the first line of a paragraph.
     */
    fun pageStartsAtBreak(text: String, pageStart: Int, pageEnd: Int, headingStarts: List<Int>): Boolean {
        if (pageStart <= 0) return true
        if (headingStarts.any { it in pageStart until pageEnd.coerceAtLeast(pageStart + 1) }) return true
        // Back over whatever whitespace sits between the last page and this one:
        // a blank line in it means the last page ended with its paragraph.
        var i = pageStart.coerceAtMost(text.length) - 1
        var newlines = 0
        while (i >= 0 && text[i].isWhitespace()) {
            if (text[i] == '\n') newlines++
            i--
        }
        // The page itself may open with the blank line.
        var j = pageStart
        while (j < text.length && text[j].isWhitespace()) {
            if (text[j] == '\n') newlines++
            j++
        }
        return newlines >= 2
    }

    /** Whether an EPUB page starts between two thoughts: a new chapter, or its first page. */
    fun chapterBreak(previousChapter: Int?, chapter: Int, fraction: Float): Boolean =
        (previousChapter != null && previousChapter != chapter) || fraction <= 0.005f

    private fun grouped(n: Int): String = "%,d".format(java.util.Locale.US, n)
}

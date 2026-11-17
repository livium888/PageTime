package com.pagetime.app.data.learning

/**
 * The one deliberate pause at the end of a chapter: say, in a sentence, what it
 * was about.
 *
 * WHY THIS AND NOT A QUIZ
 *
 * Remembering comes from pulling something out of your head, not from reading
 * it again, and putting it in your own words is the cheapest way to do that.
 * Nothing here is marked and no AI is involved: the reader writes a sentence,
 * can look at how the chapter began, and carries on. What they wrote is not
 * kept. The point is the attempt.
 *
 * It is asked once per chapter, only at a chapter that was read through (not
 * jumped to), only after a chapter long enough to be worth recalling, and
 * never for a chapter whose end the reader skipped.
 *
 * Pure, so the rules can be tested without a reader.
 */
object ChapterRecall {

    /** A shorter chapter is a few paragraphs, not something to summarise. */
    const val MIN_CHAPTER_WORDS = 800

    /** The reader must have got this far through the chapter before leaving it. */
    const val READ_THROUGH = 0.85f

    /** How much of the chapter's opening is shown for comparison. */
    const val OPENING_WORDS = 70

    /**
     * Whether to ask about the chapter just finished.
     *
     * @param leftChapter the chapter the reader was in
     * @param enteredChapter the chapter they are in now
     * @param fractionWhenLeft how far through [leftChapter] they were on their last page there
     * @param chapterWords the length of [leftChapter], or null if not counted yet
     */
    fun shouldAsk(
        leftChapter: Int,
        enteredChapter: Int,
        fractionWhenLeft: Float,
        chapterWords: Int?,
        alreadyAsked: Set<Int>,
        enabled: Boolean,
    ): Boolean =
        enabled &&
            enteredChapter == leftChapter + 1 &&
            fractionWhenLeft >= READ_THROUGH &&
            (chapterWords ?: 0) >= MIN_CHAPTER_WORDS &&
            leftChapter !in alreadyAsked

    /**
     * The first [words] words of [chapterText], whole, with an ellipsis if it
     * goes on. Blank lines are kept as paragraph breaks.
     */
    fun opening(chapterText: String, words: Int = OPENING_WORDS): String {
        val trimmed = chapterText.trim()
        if (trimmed.isEmpty()) return ""
        var count = 0
        var end = 0
        val word = Regex("\\S+")
        for (match in word.findAll(trimmed)) {
            count++
            end = match.range.last + 1
            if (count == words) break
        }
        val taken = trimmed.substring(0, end)
        val more = word.findAll(trimmed.substring(end)).any()
        return if (more) "$taken…" else taken
    }
}

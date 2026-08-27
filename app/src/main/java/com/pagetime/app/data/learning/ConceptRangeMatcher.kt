package com.pagetime.app.data.learning

/**
 * Decides whether a stored concept is actually grounded in a given reading
 * range. A concept counts as relevant only when its source quote or its label
 * can be found in the range's text.
 *
 * This is the guard that stops concepts from unrelated parts of a book (other
 * sections of the same chapter, later checkpoints, or other chapters) from
 * being offered for the passage the reader is actually looking at.
 *
 * Matching is tolerant of extraction noise: curly quotes, dashes, and repeated
 * whitespace are normalized away before comparison, and a quote that nearly
 * matches word-for-word still counts.
 */
object ConceptRangeMatcher {

    /** Quotes shorter than this are too generic to trust as a containment match. */
    private const val MIN_QUOTE_LENGTH = 12

    /** Labels shorter than this (normalized) are too generic to trust either. */
    private const val MIN_LABEL_LENGTH = 4

    /** Share of a quote's significant words that must appear in the range. */
    private const val FUZZY_WORD_MATCH_RATIO = 0.8f

    private const val MIN_FUZZY_WORD_LENGTH = 5

    fun isRelevant(label: String, sourceQuote: String?, rangeText: String): Boolean {
        // Space-padding turns containment into whole-phrase matching, so a
        // label like "Republic" cannot match inside "Republican".
        val paddedRange = " ${normalize(rangeText)} "
        if (paddedRange.isBlank()) return false

        val normalizedQuote = normalize(sourceQuote.orEmpty())
        if (normalizedQuote.length >= MIN_QUOTE_LENGTH && paddedRange.contains(" $normalizedQuote ")) {
            return true
        }

        val normalizedLabel = normalize(label)
        if (normalizedLabel.length >= MIN_LABEL_LENGTH && paddedRange.contains(" $normalizedLabel ")) {
            return true
        }

        // EPUB text extraction can drift by a character or two; fall back to
        // word-level overlap for long quotes before giving up.
        if (normalizedQuote.length >= MIN_QUOTE_LENGTH) {
            val words = normalizedQuote.split(" ")
                .filter { it.length >= MIN_FUZZY_WORD_LENGTH }
                .distinct()
            if (words.isNotEmpty()) {
                val matched = words.count { paddedRange.contains(" $it ") }
                if (matched.toFloat() / words.size >= FUZZY_WORD_MATCH_RATIO) return true
            }
        }
        return false
    }

    /** Lowercases and strips punctuation so quotes survive extraction drift. */
    fun normalize(value: String): String = value
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('–', '-')
        .replace('—', '-')
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

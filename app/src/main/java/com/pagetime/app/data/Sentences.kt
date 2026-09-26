package com.pagetime.app.data

/**
 * Sentence boundaries, so a thumb can point at one.
 *
 * Marking text on a phone means dragging two handles onto exact characters,
 * which is the one gesture a six-inch screen is worst at. A reader almost never
 * wants the word "the blockade" — they want the sentence it sits in. This finds
 * that sentence from wherever they pressed, and [next] / [previous] step one
 * sentence at a time out of it, so the whole job becomes taps instead of drags.
 *
 * Deliberately pure and free of Android: this is the part of the feature that
 * can be tested exhaustively without a device, and it is the part that decides
 * whether a highlight holds the right words.
 *
 * The rules, in the order they are applied:
 *
 *  - A paragraph — text separated by a blank line — is the outer unit, and
 *    nothing spans one.
 *  - Inside it, `.` `!` `?` `…` and their CJK equivalents end a sentence, along
 *    with any closing quote or bracket that follows, and any repeat — so `?!`
 *    and `.”` are one ending, not two.
 *  - A `.` inside a number (`3.5`), after a known abbreviation (`Mr.`, `etc.`,
 *    `Fig. 3`) or after an initial (`J. R. R.`) is not an ending.
 *  - A paragraph with no punctuation at all falls back to line breaks, so a
 *    bulleted list or a poem is selectable line by line instead of as a wall.
 *
 * An abbreviation that is not on the list will split a sentence in two. That is
 * the deliberate direction to fail in: the arrows step sentence by sentence, so
 * a wrong boundary costs one tap to fix, whereas a *merged* sentence — what a
 * looser rule produces — cannot be fixed at all.
 */
object Sentences {

    /** A half-open character range: [start] is included, [end] is not. */
    data class Span(val start: Int, val end: Int) {

        val isEmpty: Boolean get() = end <= start

        fun covers(offset: Int): Boolean = offset >= start && offset < end

        /**
         * The smallest span covering both, gap included.
         *
         * Stepping outwards produces neighbouring spans with whitespace between
         * them, and a highlight that skipped that whitespace would render as two
         * runs glued together mid-word.
         */
        fun through(other: Span): Span = Span(minOf(start, other.start), maxOf(end, other.end))
    }

    /**
     * The sentence containing [offset], or null for blank text.
     *
     * A point in the whitespace *between* two sentences belongs to the one it
     * follows: a reader pressing just past a full stop means the sentence they
     * just read, not the one they have not started.
     */
    fun spanAt(text: String, offset: Int): Span? {
        if (text.isBlank()) return null
        val at = offset.coerceIn(0, text.lastIndex)
        val paragraph = paragraphAt(text, at) ?: return null
        val spans = sentenceSpans(text, paragraph)
        if (spans.isEmpty()) return paragraph
        return spans.lastOrNull { it.start <= at } ?: spans.first()
    }

    /** The paragraph containing [offset]; the fallback unit when nothing splits. */
    fun paragraphAt(text: String, offset: Int): Span? {
        if (text.isBlank()) return null
        val at = offset.coerceIn(0, text.lastIndex)

        var paragraphStart = 0
        var i = at - 1
        while (i >= 0) {
            if (text[i] == '\n') {
                val secondBreak = blankLineBreakAt(text, i + 1)
                if (secondBreak != null) {
                    paragraphStart = secondBreak + 1
                    break
                }
            }
            i--
        }

        var paragraphEnd = text.length
        var k = at
        while (k < text.length) {
            if (text[k] == '\n' && blankLineBreakAt(text, k + 1) != null) {
                paragraphEnd = k
                break
            }
            k++
        }

        return trimmed(text, Span(paragraphStart, paragraphEnd))
    }

    /**
     * The sentence after [span], or null at the end of the text.
     *
     * Stepping is directional and does not re-derive [span]'s own boundaries,
     * which is what makes a step outwards from an already-extended selection
     * land on the *next* sentence rather than re-selecting what is already held.
     */
    fun next(text: String, span: Span): Span? {
        var i = span.end
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        val found = spanAt(text, i) ?: return null
        return found.takeIf { it.start >= span.end }
    }

    /** The sentence before [span], or null at the start of the text. */
    fun previous(text: String, span: Span): Span? {
        var i = span.start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return null
        val found = spanAt(text, i) ?: return null
        return found.takeIf { it.end <= span.start && it.start < span.start }
    }

    // ── Boundaries ─────────────────────────────────────────────────────────

    /**
     * Splits [paragraph] into sentences, preferring punctuation but falling back
     * to line breaks when no punctuation in it ends anything.
     *
     * The fallback is not decoration: a transcript's speaker lines and a
     * bulleted list have no full stops in them, and without it "one sentence"
     * would mean the entire page.
     *
     * The test is whether any terminator *ends* a sentence rather than whether
     * dots exist, which is what keeps a wrapped line from cutting a sentence in
     * half: PDF extraction wraps prose mid-sentence, and text that has ending
     * punctuation is trusted to say where its own sentences stop.
     */
    private fun sentenceSpans(text: String, paragraph: Span): List<Span> {
        val byStops = splitOnStops(text, paragraph)
        if (hasSentenceEnd(text, paragraph)) return byStops
        val byLines = splitOnLines(text, paragraph)
        return if (byLines.size > 1) byLines else byStops
    }

    private fun hasSentenceEnd(text: String, bounds: Span): Boolean {
        var i = bounds.start
        while (i < bounds.end) {
            if (isTerminator(text[i]) && endsSentence(text, i)) return true
            i++
        }
        return false
    }

    private fun splitOnStops(text: String, bounds: Span): List<Span> {
        val spans = mutableListOf<Span>()
        var cursor = bounds.start
        var i = bounds.start
        while (i < bounds.end) {
            if (isTerminator(text[i]) && endsSentence(text, i)) {
                var j = i + 1
                while (j < bounds.end && (isTerminator(text[j]) || isCloser(text[j]))) j++
                addSpan(spans, text, cursor, j)
                cursor = j
                i = j
                continue
            }
            i++
        }
        addSpan(spans, text, cursor, bounds.end)
        return spans
    }

    private fun splitOnLines(text: String, bounds: Span): List<Span> {
        val spans = mutableListOf<Span>()
        var cursor = bounds.start
        var i = bounds.start
        while (i < bounds.end) {
            if (text[i] == '\n') {
                addSpan(spans, text, cursor, i)
                cursor = i + 1
            }
            i++
        }
        addSpan(spans, text, cursor, bounds.end)
        return spans
    }

    /** Whether the terminator at [at] really ends a sentence. */
    private fun endsSentence(text: String, at: Int): Boolean {
        val c = text[at]
        if (c != '.') return true
        // A decimal point, not a full stop: 3.5. Checked before the token rules
        // because "3" and "5" are both digits and the token before "3.5" is
        // meaningless.
        if (text.getOrNull(at - 1)?.isDigit() == true && text.getOrNull(at + 1)?.isDigit() == true) {
            return false
        }
        val start = tokenStart(text, at)
        if (start == at) return true
        return !isAbbreviation(text, text.substring(start, at), start, at)
    }

    private fun isAbbreviation(text: String, token: String, tokenStart: Int, dotIndex: Int): Boolean {
        val lower = token.lowercase()

        // A numbered list: "1. First item" is numbering. A year is not —
        // "in 1999. He left" ends a sentence, and only a line that begins with
        // the number is treated as a marker.
        if (token.all { it.isDigit() }) return startsLine(text, tokenStart)

        // Initials and dotted abbreviations: "J.", "U.S.", "e.g.", "i.e.".
        if (lower.split('.').all { it.length <= 1 }) return true

        if (lower in ABBREVIATIONS) return true

        if (lower in REFERENCE_ABBREVIATIONS) {
            val next = nextNonSpace(text, dotIndex) ?: return false
            if (next.isDigit()) return true
            // Capitalised before a capitalised word — "No. Five", "Fig. A" — is
            // a reference. A lowercase word before an uppercase one — "he said
            // no. Then…" — is the end of a sentence.
            return token.firstOrNull()?.isUpperCase() == true && next.isUpperCase()
        }

        // Months, weekdays and am/pm are ordinary English words too, and a date
        // is always followed by a number: "in Aug. Then it rained" is two
        // sentences, "Aug. 5" is one.
        if (lower in DIGIT_ONLY_ABBREVIATIONS) {
            return nextNonSpace(text, dotIndex)?.isDigit() == true
        }

        return false
    }

    private fun tokenStart(text: String, at: Int): Int {
        var i = at
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '.')) i--
        return i
    }

    private fun startsLine(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n') return true
            if (!c.isWhitespace()) return false
            i--
        }
        return true
    }

    private fun nextNonSpace(text: String, from: Int): Char? {
        var i = from + 1
        while (i < text.length && text[i].isWhitespace()) i++
        return text.getOrNull(i)
    }

    /**
     * The index of the second break of the blank line starting at [from], or
     * null when the line is not blank.
     */
    private fun blankLineBreakAt(text: String, from: Int): Int? {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
        return if (i < text.length && text[i] == '\n') i else null
    }

    private fun isTerminator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…' ||
            c == '。' || c == '！' || c == '？' || c == '؟' || c == '۔' ||
            c == '।'

    private fun isCloser(c: Char): Boolean =
        c == '"' || c == '\'' || c == '”' || c == '’' || c == '»' || c == '›' ||
            c == ')' || c == ']' || c == '}' || c == '）' || c == '】' ||
            c == '」' || c == '』' || c == '〕'

    private fun addSpan(into: MutableList<Span>, text: String, start: Int, end: Int) {
        val span = trimmed(text, Span(start, end))
        if (!span.isEmpty) into += span
    }

    /** Shrinks a span to its first and last non-whitespace characters. */
    private fun trimmed(text: String, span: Span): Span {
        var start = span.start.coerceIn(0, text.length)
        var end = span.end.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return Span(start, end)
    }

    /**
     * Abbreviations that are never the last word of a sentence, so a `.` after
     * one always continues it.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "mx", "dr", "prof", "rev", "hon", "sr", "jr",
        "st", "mt", "vs", "etc", "cf", "al", "approx", "est", "inc", "ltd",
        "co", "corp", "dept", "univ", "esp", "misc", "gov", "natl", "intl",
        "assn", "bros", "phd", "ba", "ma", "blvd", "ave", "rd",
    )

    /**
     * Abbreviations that only abbreviate when something referential follows —
     * `Fig. 3`, `No. Five`. Their lowercase forms are ordinary English words, so
     * treating a trailing `.` after them as an abbreviation unconditionally
     * swallowed the end of sentences like "he said no. Then he left."
     */
    private val REFERENCE_ABBREVIATIONS = setOf(
        "no", "nos", "p", "pp", "ch", "chs", "fig", "figs", "vol", "vols",
        "ed", "eds", "eq", "eqs", "ca", "bk", "sec", "para", "ref", "refs",
    )

    /** Abbreviated only in front of a number: `Aug. 5`, `Sun. 12`, `9 a.m.` */
    private val DIGIT_ONLY_ABBREVIATIONS = setOf(
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct",
        "nov", "dec", "mon", "tue", "tues", "wed", "thu", "thur", "thurs",
        "fri", "sat", "sun", "am", "pm",
    )
}

package com.pagetime.app.data.library

/**
 * Turns the lines a PDF stripper produces into paragraphs a person can read.
 *
 * Text extraction gives back what the page LOOKS like, not what it reads like.
 * Every visual line arrives as its own line, so a paragraph of twelve lines
 * arrives as twelve lines; running heads and page numbers arrive mixed in with
 * the prose, once per page; and a word the layout split across two lines
 * arrives with the hyphen still in it. Read as-is, a book comes out as hundreds
 * of one-line fragments with the chapter title and "37" sprinkled through it —
 * which is why extracting text is not the same as making a book.
 *
 * Three passes fix that, each with a rule that can be argued with:
 *
 *  1. **Furniture.** A line seen in the top or bottom two lines of most pages
 *     is a running head or footer, and a repeated line is not prose, so it is
 *     dropped wherever it appears. Standalone page numbers go too, even though
 *     each page's is different - repetition cannot catch those.
 *  2. **Rejoining.** Lines are joined into one paragraph, except where the line
 *     before ended short — a sentence ending well before the right margin, or a
 *     line far too short to be prose at all, which is what a chapter title
 *     looks like next to a paragraph. A line is broken by the layout when it
 *     reaches the margin; it ends because something ended when it does not.
 *  3. **Hyphens.** "co-" at the end of a line followed by "operate" is one
 *     word, so the hyphen and the break both go. "1920-" followed by "1945"
 *     keeps its dash, because a word does not start with a digit.
 *
 * It is deliberately not clever. Two-column pages still interleave, because
 * nothing here knows where a column ends. Nothing here can invent text a page
 * image never had. Those need the page itself, not this.
 *
 * Pure Kotlin on purpose: no Android and no PDFBox, so the rules can be read
 * and reasoned about (and tested) on their own.
 */
internal object PdfTextCleaner {

    /**
     * One tidied text per page, the list in the same order it came in.
     *
     * Page by page rather than the whole document at once because the reader
     * gets one document per page — which is also what makes a figure's page
     * unambiguous. The furniture pass still sees the whole document, since a
     * running head is only recognisable by its repetition across pages.
     */
    fun cleanPerPage(pages: List<String>): List<String> {
        val running = runningLines(pages)
        return pages.map { page ->
            paragraphs(page.lines(), running).joinToString(BLANK_LINE)
        }
    }

    /**
     * The lines that repeat at the margins of most pages, normalized. Empty for
     * a document too short to tell a running head from a chapter title.
     */
    private fun runningLines(pages: List<String>): Set<String> {
        if (pages.size < MIN_PAGES_FOR_FURNITURE) return emptySet()
        val sightings = HashMap<String, Int>()
        for (page in pages) {
            val lines = page.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val margins = lines.take(MARGIN_LINES) + lines.takeLast(MARGIN_LINES)
            margins
                .map(::key)
                .filter { it.length >= MIN_FURNITURE_LENGTH }
                // Once per page, however many times the line appears on it.
                .distinct()
                .forEach { seen -> sightings[seen] = (sightings[seen] ?: 0) + 1 }
        }
        val threshold = maxOf(2, (pages.size * FURNITURE_SHARE).toInt())
        return sightings.filterValues { it >= threshold }.keys
    }

    /** A page's paragraphs. Blank lines the document actually made are kept. */
    private fun paragraphs(lines: List<String>, running: Set<String>): List<String> =
        blocks(lines).mapNotNull { block ->
            val kept = block.filterNot { isFurniture(it, running) || isPageNumber(it) }
            if (kept.isEmpty()) null else reflow(kept)
        }

    /** Groups of consecutive non-blank lines: a blank line ends a group. */
    private fun blocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (blocks.lastOrNull()?.isNotEmpty() == true) blocks.add(mutableListOf())
            } else {
                if (blocks.isEmpty()) blocks.add(mutableListOf())
                blocks.last().add(line)
            }
        }
        return blocks.filter { it.isNotEmpty() }
    }

    /** One block of consecutive lines as one or more paragraphs. */
    private fun reflow(lines: List<String>): String {
        val median = medianLength(lines)
        val text = StringBuilder()
        var previous: String? = null
        for (line in lines) {
            val last = previous
            when {
                text.isEmpty() -> text.append(line)

                startsBlock(line) || endedParagraph(last.orEmpty(), median) ->
                    text.append(BLANK_LINE).append(line)

                joinsAcrossHyphen(last.orEmpty(), line) -> {
                    // The hyphen is the layout's, not the word's.
                    text.deleteCharAt(text.length - 1)
                    text.append(line)
                }

                else -> text.append(' ').append(line)
            }
            previous = line
        }
        return text.toString()
    }

    /**
     * True when [line] ends a paragraph or stands on its own as a heading.
     *
     * Two ways the line before ends something. It ended a sentence well short of the
     * right margin — a line broken by the layout reaches the margin, so a
     * sentence that stopped early stopped because the paragraph did. Or it is
     * far too short to be prose at all, which is how a chapter title reads on a
     * page where every other line reaches the margin. Both compare against the
     * page's own median line rather than a fixed width, because a line is
     * "short" relative to the column it is set in.
     */
    private fun endedParagraph(previous: String, median: Int): Boolean {
        if (median <= 0 || previous.length >= median * SHORT_LINE_SHARE) return false
        val last = previous.lastOrNull() ?: return false
        return last in SENTENCE_END || previous.length < median * HEADING_SHARE
    }

    /** A bullet, or a numbered or lettered item, begins its own paragraph. */
    private fun startsBlock(line: String): Boolean = BLOCK_START.containsMatchIn(line)

    private fun joinsAcrossHyphen(previous: String, line: String): Boolean {
        val last = previous.lastOrNull() ?: return false
        if (last !in HYPHENS) return false
        // "1920-" before "1945" is a dash the reader wants. "co-" before
        // "operate" is a word the layout cut in half.
        return line.firstOrNull()?.isLowerCase() == true
    }

    private fun isFurniture(line: String, running: Set<String>): Boolean {
        val normalized = key(line)
        return normalized.length >= MIN_FURNITURE_LENGTH && normalized in running
    }

    private fun isPageNumber(line: String): Boolean =
        PAGE_NUMBER.matches(line) || ROMAN_PAGE_NUMBER.matches(line)

    /**
     * A line reduced to what it has in common with itself on other pages.
     * Digits become "#" so "Chapter Four — 12" and "Chapter Four — 13" are the
     * same running head, and so a footer whose number changes is still caught.
     */
    private fun key(line: String): String = line
        .lowercase()
        .replace(DIGITS, "#")
        .replace(WHITESPACE, " ")
        .trim()

    private fun medianLength(lines: List<String>): Int {
        if (lines.isEmpty()) return 0
        val lengths = lines.map { it.length }.sorted()
        return lengths[lengths.size / 2]
    }

    private val DIGITS = Regex("[0-9]+")
    private val WHITESPACE = Regex("\\s+")

    /** Punctuation that ends a sentence, including a closing quote or bracket. */
    private const val SENTENCE_END = ".!?…\":;"

    /** A word split across a line can end in a plain hyphen or a soft one. */
    private const val HYPHENS = "-\u00AD\u2010"

    private val BLOCK_START = Regex(
        """^(?:[-•*·–—]|\(?\d{1,3}[.)]|\(?[a-z][.)]|\[?\d{1,3}\])\s+\S"""
    )

    private val PAGE_NUMBER = Regex(
        "^(?:page\\s+)?[-–—|]?\\s*\\d{1,4}\\s*(?:of\\s+\\d{1,4})?[-–—|]?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Roman numeral page numbers: common in a book's front matter, and
     * different on every page, so the repeating-line pass cannot see them.
     * Uppercase and two characters or more — "civil" and "mild" are made of
     * the same letters and are lines of prose.
     */
    private val ROMAN_PAGE_NUMBER = Regex("^[-–—|]?\\s*[IVXLCDM]{2,7}\\s*[-–—|]?$")

    private const val BLANK_LINE = "\n\n"

    /** Below this, a repeated margin line is a coincidence rather than a header. */
    private const val MIN_PAGES_FOR_FURNITURE = 4

    /** How many lines at each end of a page count as its margins. */
    private const val MARGIN_LINES = 2

    /** Shorter than this, a "repeated" line is too generic to drop. */
    private const val MIN_FURNITURE_LENGTH = 3

    /** The share of pages a margin line must appear on to be furniture. */
    private const val FURNITURE_SHARE = 0.6

    /** How far short of the median line a paragraph's last line is expected to be. */
    private const val SHORT_LINE_SHARE = 0.75f

    /** Below this share of the median line, a line is a heading, not prose. */
    private const val HEADING_SHARE = 0.45f
}

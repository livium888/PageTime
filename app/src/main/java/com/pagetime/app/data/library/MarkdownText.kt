package com.pagetime.app.data.library

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns pasted markdown into the clean text a book is stored as, plus the
 * ranges of that text to draw as headings, bold and italic.
 *
 * WHY THE SYMBOLS ARE REMOVED RATHER THAN HIDDEN
 *
 * Everything the text reader does is counted in characters of the stored
 * text: pages, highlights, the span cards are made from, the reading position.
 * Hiding `**` at draw time would make the text on screen a different length
 * from the text those offsets point into. So the markers are removed once,
 * here, and what they meant is kept beside the text as [StyleSpan]s whose
 * offsets are into the clean text. The reader adds them as one more style
 * layer and nothing else has to know the book was ever markdown.
 *
 * Plain text passes through unchanged: a lone asterisk or an underscore inside
 * a word is not a marker, and only a pair around text on one line is.
 */
object MarkdownText {

    enum class Kind { HEADING, BOLD, ITALIC }

    /** [level] is 1 to 6 for a [Kind.HEADING] (`#` to `######`) and 0 for everything else. */
    data class StyleSpan(val start: Int, val end: Int, val kind: Kind, val level: Int = 0)

    data class Converted(val text: String, val spans: List<StyleSpan>)

    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val FENCE = Regex("""^\s{0,3}(```|~~~).*$""")

    /** `| --- | :---: |`: the line under a table's header. At least two columns. */
    private val TABLE_DIVIDER = Regex("""^\s{0,3}\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)+\|?\s*$""")

    fun convert(markdown: String): Converted {
        val out = StringBuilder()
        val spans = mutableListOf<StyleSpan>()
        var inFence = false

        fun endsWithBlankLine() = out.isEmpty() || out.endsWith("\n\n")
        fun ensureBlankLine() {
            if (out.isEmpty()) return
            while (!out.endsWith("\n\n")) out.append('\n')
        }

        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index++].trimEnd()
            if (FENCE.matches(line)) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                out.append(line).append('\n')
                continue
            }
            if (line.contains('|') && index < lines.size && TABLE_DIVIDER.matches(lines[index].trimEnd())) {
                val header = splitRow(line)
                index++
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                    rows += splitRow(lines[index].trimEnd())
                    index++
                }
                ensureBlankLine()
                for (row in rows) table(header, row, out, spans)
                continue
            }
            if (line.isBlank() || RULE.matches(line)) {
                if (!endsWithBlankLine()) out.append('\n')
                continue
            }
            val heading = HEADING.find(line)
            if (heading != null && heading.groupValues[2].isNotBlank()) {
                ensureBlankLine()
                val start = out.length
                inline(heading.groupValues[2], out, spans)
                if (out.length > start) {
                    spans += StyleSpan(start, out.length, Kind.HEADING, heading.groupValues[1].length)
                }
                out.append("\n\n")
                continue
            }
            val bullet = BULLET.find(line)
            val quote = if (bullet == null) QUOTE.find(line) else null
            when {
                bullet != null -> {
                    out.append(bullet.groupValues[1]).append("• ")
                    inline(bullet.groupValues[2], out, spans)
                }
                quote != null -> {
                    // A quotation is set in italics: its mark is gone, so this
                    // is what still tells it from the speaker's own words.
                    val start = out.length
                    inline(quote.groupValues[1], out, spans)
                    if (out.length > start) spans += StyleSpan(start, out.length, Kind.ITALIC)
                }
                else -> inline(line, out, spans)
            }
            out.append('\n')
        }

        val text = out.toString().trimEnd()
        return Converted(
            text = text,
            spans = spans.mapNotNull { span ->
                val end = span.end.coerceAtMost(text.length)
                if (end > span.start) span.copy(end = end) else null
            },
        )
    }

    /**
     * One table row as a small block, because a table cannot be drawn on a
     * phone: the page is a column of wrapped text, a few characters wide per
     * column, and a table's cells would wrap into an unreadable grid.
     *
     * The row's first cell is its title, in bold; every other cell goes on its
     * own line as "Column: value" with the column name in italics. Nothing in
     * the table is lost except the first column's own heading, which is the
     * title's role and says nothing the titles do not. Empty cells are skipped.
     */
    private fun table(header: List<String>, row: List<String>, out: StringBuilder, spans: MutableList<StyleSpan>) {
        if (row.all { it.isBlank() }) return
        var wrote = false
        fun newLine() {
            if (wrote) out.append('\n')
            wrote = true
        }
        row.forEachIndexed { column, cell ->
            if (cell.isBlank()) return@forEachIndexed
            newLine()
            if (column == 0) {
                val start = out.length
                inline(cell, out, spans)
                if (out.length > start) spans += StyleSpan(start, out.length, Kind.BOLD)
            } else {
                val name = header.getOrNull(column).orEmpty()
                if (name.isNotBlank()) {
                    val start = out.length
                    inline(name, out, spans)
                    if (out.length > start) spans += StyleSpan(start, out.length, Kind.ITALIC)
                    out.append(": ")
                }
                inline(cell, out, spans)
            }
        }
        out.append("\n\n")
    }

    /** The cells of a table row, without its outer pipes; `\|` stays inside its cell. */
    private fun splitRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> {
                    current.append("\\|")
                    i += 2
                }
                c == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        cells += current.toString().trim()
        // "| a | b |" splits into "", "a", "b", "": the outer pipes are not cells.
        if (line.trimStart().startsWith("|") && cells.isNotEmpty()) cells.removeAt(0)
        if (line.trimEnd().endsWith("|") && !line.trimEnd().endsWith("\\|") && cells.isNotEmpty()) cells.removeAt(cells.size - 1)
        return cells
    }

    /**
     * The headings that open a section worth a pause, a "talk it through" offer
     * or a section receipt: the two shallowest levels the text uses. A
     * transcript with one `#` and a dozen `##` is divided by its `##`s; one
     * that never goes above `##` is divided by those and its `###`s. Deeper
     * headings are still drawn and listed, but do not split the reading up.
     */
    fun sectionHeadings(spans: List<StyleSpan>): List<StyleSpan> {
        val headings = spans.filter { it.kind == Kind.HEADING }
        val top = headings.minOfOrNull { it.level } ?: return emptyList()
        return headings.filter { it.level <= top + 1 }
    }

    /** Appends [source] to [out] with its inline markers removed, recording what they meant. */
    private fun inline(source: String, out: StringBuilder, spans: MutableList<StyleSpan>) {
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < n && source[i + 1] in ESCAPABLE -> {
                    out.append(source[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val close = source.indexOf('`', i + 1)
                    if (close > i) {
                        out.append(source, i + 1, close)
                        i = close + 1
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '!' && i + 1 < n && source[i + 1] == '[' -> {
                    val link = linkAt(source, i + 1)
                    if (link != null) {
                        out.append(link.first)
                        i = link.second
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '[' -> {
                    val link = linkAt(source, i)
                    if (link != null) {
                        inline(link.first, out, spans)
                        i = link.second
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '*' || c == '_' -> {
                    var run = 1
                    while (i + run < n && source[i + run] == c) run++
                    val width = run.coerceAtMost(3)
                    val close = closingRun(source, i, width, c)
                    if (close < 0) {
                        out.append(source, i, i + run)
                        i += run
                    } else {
                        val start = out.length
                        inline(source.substring(i + width, close), out, spans)
                        if (out.length > start) {
                            if (width >= 2) spans += StyleSpan(start, out.length, Kind.BOLD)
                            if (width != 2) spans += StyleSpan(start, out.length, Kind.ITALIC)
                        }
                        i = close + width
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /**
     * Where the run of [width] [marker]s opening at [open] closes, or -1 when
     * this is not emphasis: nothing follows it, the text inside starts or ends
     * with a space, or an underscore sits inside a word (snake_case).
     */
    private fun closingRun(source: String, open: Int, width: Int, marker: Char): Int {
        val n = source.length
        val innerStart = open + width
        if (innerStart >= n || source[innerStart].isWhitespace()) return -1
        if (marker == '_' && open > 0 && source[open - 1].isLetterOrDigit()) return -1
        var j = innerStart + 1
        while (j + width <= n) {
            if (source[j] == marker && source[j - 1] != '\\') {
                var run = 1
                while (j + run < n && source[j + run] == marker) run++
                if (run == width && !source[j - 1].isWhitespace()) {
                    val after = j + width
                    val insideWord = marker == '_' && after < n && source[after].isLetterOrDigit()
                    if (!insideWord) return j
                }
                j += run
            } else {
                j++
            }
        }
        return -1
    }

    /** `[text](target)` starting at [open]: the text and the index just past the link, or null. */
    private fun linkAt(source: String, open: Int): Pair<String, Int>? {
        var depth = 0
        var j = open
        while (j < source.length) {
            when (source[j]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            j++
        }
        if (j >= source.length || j + 1 >= source.length || source[j + 1] != '(') return null
        val close = source.indexOf(')', j + 2)
        if (close < 0) return null
        return source.substring(open + 1, j) to close + 1
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!>~|"

    /** A title for the pasted text: its first heading, else its first line. */
    fun suggestTitle(converted: Converted): String {
        val heading = converted.spans.firstOrNull { it.kind == Kind.HEADING }
        val candidate = heading?.let { converted.text.substring(it.start, it.end) }
            ?: converted.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val clean = candidate.trim().trimStart('•', ' ')
        return when {
            clean.isEmpty() -> "Pasted text"
            clean.length <= MAX_TITLE -> clean
            else -> clean.take(MAX_TITLE).substringBeforeLast(' ').ifBlank { clean.take(MAX_TITLE) } + "…"
        }
    }

    private const val MAX_TITLE = 60

    // region The sidecar file: style ranges stored beside the book's text

    /**
     * [textLength] is stored with the spans so they can be recognised as stale:
     * once the book's text is replaced or re-formatted, offsets into the old
     * text would style arbitrary words of the new one.
     */
    fun encode(textLength: Int, spans: List<StyleSpan>): String =
        JSONObject()
            .put("length", textLength)
            .put(
                "spans",
                JSONArray().apply {
                    spans.forEach { put(JSONArray().put(it.start).put(it.end).put(it.kind.name).put(it.level)) }
                },
            )
            .toString()

    /** The spans in [raw], or none when it is unreadable or was made for different text. */
    fun decode(raw: String?, textLength: Int): List<StyleSpan> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            if (root.optInt("length", -1) != textLength) return emptyList()
            val array = root.optJSONArray("spans") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONArray(index) ?: return@mapNotNull null
                val kind = Kind.entries.firstOrNull { it.name == item.optString(2) } ?: return@mapNotNull null
                val start = item.optInt(0, -1)
                val end = item.optInt(1, -1)
                // Files written before levels existed have none: those headings were all drawn alike.
                val level = if (kind == Kind.HEADING) item.optInt(3, 1).coerceIn(1, 6) else 0
                if (start < 0 || end <= start || end > textLength) null else StyleSpan(start, end, kind, level)
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** [spans] clipped to one page and re-based to that page's own text. */
    fun pageSpans(spans: List<StyleSpan>, pageStart: Int, pageEnd: Int): List<StyleSpan> =
        spans.mapNotNull { span ->
            val start = span.start.coerceAtLeast(pageStart)
            val end = span.end.coerceAtMost(pageEnd)
            if (end > start) StyleSpan(start - pageStart, end - pageStart, span.kind, span.level) else null
        }

    // endregion
}

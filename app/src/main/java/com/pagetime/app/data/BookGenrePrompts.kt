package com.pagetime.app.data

/**
 * Sorting the library by genre with the reader's own AI app: one prompt for
 * every untagged book, and a reader for the reply.
 *
 * One prompt for the whole list rather than one per book, because each prompt
 * is a trip to another app and back. The books are numbered and the reply is
 * read by number, so a title the model rewords or misspells still lands on the
 * right book.
 */
object BookGenrePrompts {
    private val LABELS = BookGenre.entries.joinToString(", ") { it.label }

    /** How many books one prompt carries. More than this and replies start dropping lines. */
    const val MAX_BOOKS = 40

    fun classifyAll(books: List<Pair<String, String>>): String {
        val list = books.take(MAX_BOOKS).mapIndexed { index, (title, author) ->
            "${index + 1}. ${title.trim()}" + author.trim().takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        }.joinToString("\n")
        return """
            |Classify each book below into exactly one of these categories: $LABELS.
            |
            |$list
            |
            |Reply with one line per book, in this exact form and nothing else:
            |<number>: <category>
            |Use the category names exactly as written in the list above. If you don't
            |recognize a book well enough to be confident, use "Other".
        """.trimMargin()
    }

    private val LINE = Regex("""^\W*(\d+)\s*[:.)\-–—]\s*(.+?)\s*$""")

    /**
     * The genre given to each numbered book (numbers from 1), for [count]
     * books. Lines that are not a number and a known category are skipped, so
     * one odd line never loses the rest.
     */
    fun parseAll(reply: String, count: Int): Map<Int, BookGenre> {
        val found = linkedMapOf<Int, BookGenre>()
        for (line in reply.lines()) {
            val match = LINE.find(line.trim().trim('*', '`')) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (number !in 1..count || number in found) continue
            val genre = BookGenre.parse(match.groupValues[2].trim('*', '`', ' ')) ?: continue
            found[number] = genre
        }
        return found
    }
}

package com.pagetime.app.data

/**
 * Counts words in already-extracted plain text — deliberately not concerned
 * with where that text came from (a .txt file, an EPUB chapter stripped of
 * markup). See [BookWordCounter] for how a whole book's text reaches this.
 */
object BookWordCount {
    private val WHITESPACE = Regex("\\s+")

    fun count(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(WHITESPACE).size
    }
}

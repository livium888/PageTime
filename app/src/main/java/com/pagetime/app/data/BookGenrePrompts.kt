package com.pagetime.app.data

/** Builds the one prompt [BookGenreClassifier] sends, kept separate so it's testable without a network. */
object BookGenrePrompts {
    private val LABELS = BookGenre.entries.joinToString(", ") { it.label }

    fun classify(title: String, author: String): String = """
        |Classify this book into exactly one of these categories: $LABELS.
        |
        |Title: ${title.trim()}
        |Author: ${author.trim()}
        |
        |Reply with ONLY the category name, exactly as written in the list above — nothing else, no punctuation, no explanation. If you don't recognize this book well enough to be confident, reply with "Other".
    """.trimMargin()
}

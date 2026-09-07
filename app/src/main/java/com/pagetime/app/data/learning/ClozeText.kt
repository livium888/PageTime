package com.pagetime.app.data.learning

/**
 * Showing a cloze card to a person.
 *
 * The stored form is the SuperMemo/Anki convention, `{{c1::the term}}`, which
 * is the right thing to keep in the database — it says exactly what was deleted
 * and survives being exported to anything else. It is not a thing to put in
 * front of a reader, and a card that renders its own markup reads as broken
 * software regardless of how good the question is.
 */
object ClozeText {

    /** What the reader is shown before the answer: the sentence with a gap. */
    const val GAP = "………"

    private val MARKER = Regex("""\{\{c\d+::(.+?)\}\}""")

    fun hasDeletion(text: String): Boolean = MARKER.containsMatchIn(text)

    /** The sentence with each deletion replaced by a visible gap. */
    fun blanked(text: String): String = MARKER.replace(text) { GAP }

    /** The sentence whole, for showing once the reader has answered. */
    fun filled(text: String): String = MARKER.replace(text) { it.groupValues[1] }

    /** Everything that was deleted, in order. */
    fun deletions(text: String): List<String> =
        MARKER.findAll(text).map { it.groupValues[1] }.toList()
}

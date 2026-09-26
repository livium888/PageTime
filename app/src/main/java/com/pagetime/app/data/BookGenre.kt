package com.pagetime.app.data

/**
 * A small, fixed set of categories a book can be classified into.
 *
 * Deliberately closed rather than free text: an AI asked for "the genre" in
 * its own words will happily return "cyberpunk noir romance," which is fun
 * once and useless for the actual point of this — being able to say "you
 * have 2 novels and 1 memoir" without every book inventing its own bucket.
 * [name] (not [label]) is what gets stored, so the label can be reworded
 * later without a migration.
 */
enum class BookGenre(val label: String) {
    FICTION("Fiction"),
    SCIENCE_FICTION("Science Fiction"),
    FANTASY("Fantasy"),
    MYSTERY_THRILLER("Mystery & Thriller"),
    ROMANCE("Romance"),
    POETRY("Poetry"),
    MEMOIR_BIOGRAPHY("Memoir & Biography"),
    HISTORY("History"),
    SCIENCE("Science"),
    PHILOSOPHY("Philosophy"),
    SELF_HELP("Self-Help"),
    NONFICTION("Nonfiction"),
    OTHER("Other");

    companion object {
        /**
         * Matches the model's reply against [label] or [name], case- and
         * whitespace-insensitive, since a model asked for exactly one of a
         * list will still sometimes answer "science fiction" instead of
         * "Science Fiction," or pad it with a trailing period. Null means the
         * reply didn't match anything in the list — including "Other" spelled
         * oddly — and the book is left unclassified rather than guessed at.
         */
        fun parse(raw: String): BookGenre? {
            val cleaned = raw.trim().trim('.', '"').lowercase()
            if (cleaned.isBlank()) return null
            return entries.firstOrNull { it.label.lowercase() == cleaned } ?: entries.firstOrNull {
                it.name.lowercase().replace('_', ' ') == cleaned
            }
        }
    }
}

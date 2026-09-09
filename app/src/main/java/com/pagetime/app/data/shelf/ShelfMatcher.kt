package com.pagetime.app.data.shelf

import com.pagetime.app.data.gutenberg.GutendexBook

/**
 * Deciding whether a catalogue result really is the book the ladder meant.
 *
 * WHY THIS IS NOT "TAKE THE FIRST RESULT"
 *
 * The ladder stores a title and an author, not a catalogue id, because ids
 * could not be verified when the list was written and a wrong id is the worst
 * possible failure: the reader taps Moby-Dick and gets someone else's book,
 * with nothing on screen to suggest anything went wrong. Searching instead
 * means the app can be unsure, and being unsure is recoverable.
 *
 * So the search is treated as a suggestion and this is the check. It is
 * deliberately strict — it would rather return nothing, and have the shelf say
 * "find this one yourself", than return a plausible wrong book.
 *
 * THE AUTHOR IS THE GATE
 *
 * Titles collide constantly and generically: Essays, Meditations, The Prince,
 * Ethics. Every one of those is several different books by different people,
 * and a title-only match pairs them confidently. So the author has to agree
 * before a title is even considered.
 *
 * Matching on the LAST word of the author handles both "Herman Melville"
 * against Gutenberg's "Melville, Herman" and the ancients who have no surname
 * at all — Homer, Plato, Virgil — where the last word is the whole name.
 *
 * TITLES ARE MATCHED AT THE FRONT
 *
 * Catalogue titles carry subtitles the ladder does not: "Moby Dick; Or, The
 * Whale", "Hamlet, Prince of Denmark", "Essays of Michel de Montaigne —
 * Complete". Comparing whole strings fails all of those, so one normalised
 * title has to be a prefix of the other. Leading articles are dropped first,
 * because "The Republic" and "Republic of Plato" are the same book.
 */
/**
 * A book someone wants, whoever decided they wanted it.
 *
 * The matcher was written against the ladder and took a LadderEntry, which
 * made it useless to the author shelves — where the wanting comes from the
 * reader's own library rather than a curated list. The check itself never
 * cared where the title came from, only what it is.
 */
interface WantedBook {
    val title: String
    val author: String
    /** Other spellings a catalogue might file the author under. */
    val authorAliases: List<String>
}

object ShelfMatcher {

    /** Below this a "prefix" is meaningless — two letters prefix half a catalogue. */
    private const val MIN_TITLE_PREFIX = 3

    private val ARTICLES = setOf("the", "a", "an")

    /**
     * The best result that really is this book, or null.
     *
     * Ties break on download count, which across Gutenberg is a decent proxy
     * for the edition people actually end up reading — a complete text rather
     * than volume three of five, and a modern translation rather than an
     * abandoned one.
     */
    fun bestMatch(entry: WantedBook, results: List<GutendexBook>): GutendexBook? =
        results
            .filter { it.isDownloadable() && authorAgrees(entry, it) && titleAgrees(entry.title, it.title) }
            .maxByOrNull { it.downloadCount }

    /** A catalogue entry with nothing to fetch is not a match at any confidence. */
    private fun GutendexBook.isDownloadable(): Boolean =
        !epubUrl.isNullOrBlank() || !txtUrl.isNullOrBlank()

    fun authorAgrees(entry: WantedBook, book: GutendexBook): Boolean {
        val keys = (listOf(entry.author) + entry.authorAliases).mapNotNull { surnameKey(it) }
        if (keys.isEmpty()) return false
        val listed = book.authors.joinToString(" ") { normalize(it) }
        if (listed.isBlank()) return false
        val words = listed.split(' ').toSet()
        return keys.any { it in words }
    }

    /**
     * The word to match on: the last one, which is the surname for a modern
     * name and the entire name for an ancient one.
     */
    internal fun surnameKey(author: String): String? =
        normalize(author).split(' ').lastOrNull { it.isNotBlank() && it.length >= 2 }

    internal fun titleAgrees(wanted: String, found: String): Boolean {
        val a = normalizeTitle(wanted)
        val b = normalizeTitle(found)
        if (a.length < MIN_TITLE_PREFIX || b.length < MIN_TITLE_PREFIX) return a == b && a.isNotEmpty()
        // Either may carry the subtitle, so a prefix in either direction counts
        // — but only on a word boundary, or "Ethics" would match "Ethicsology".
        return startsWithWord(b, a) || startsWithWord(a, b)
    }

    private fun startsWithWord(haystack: String, prefix: String): Boolean =
        haystack == prefix || haystack.startsWith("$prefix ")

    private fun normalizeTitle(raw: String): String {
        val words = normalize(raw).split(' ').filter { it.isNotBlank() }
        val trimmed = if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words
        return trimmed.joinToString(" ")
    }

    /** Lowercase, punctuation to spaces, whitespace collapsed. */
    internal fun normalize(raw: String): String =
        raw.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

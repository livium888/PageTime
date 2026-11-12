package com.pagetime.app.data.recommend

import com.pagetime.app.data.BookGenre
import org.json.JSONObject

/**
 * Books other readers rate highly, in the genres this reader already reads.
 *
 * WHERE THE RATINGS COME FROM
 *
 * Open Library: a public catalogue with an open search that returns, for each
 * work, the average of its readers' star ratings and how many gave one. No key
 * and no account. Goodreads closed its API to new apps in 2020 and StoryGraph
 * has none, so this is the one large set of reader ratings an app may ask.
 *
 * WHY THE AVERAGE ALONE IS NOT THE ORDER
 *
 * Sorted by average, the top of every list is a book two people gave five
 * stars. So a book needs [MIN_RATINGS] before it is listed at all, and the
 * order is the average pulled toward an ordinary score by how few ratings back
 * it (the weighting IMDb published for its Top 250). A 4.3 from three thousand
 * readers outranks a 4.8 from sixty, which is what "readers rate it highly"
 * means to a person.
 *
 * Pure: parsing and ranking can be tested without a network.
 */
object ReaderPicks {

    data class Pick(
        /** Open Library's work key, e.g. "/works/OL45804W". */
        val key: String,
        val title: String,
        val author: String?,
        val year: Int?,
        /** Average star rating, 1 to 5. */
        val rating: Float,
        /** How many readers rated it. */
        val ratings: Int,
    ) {
        val ratingLine: String
            get() = "%.1f ★ from %,d readers".format(java.util.Locale.US, rating, ratings)

        val byLine: String
            get() = listOfNotNull(author, year?.toString()).joinToString(" · ")
    }

    /** Fewer ratings than this say more about who rated than about the book. */
    const val MIN_RATINGS = 50

    /** How many ratings it takes for a book's own average to count as much as the ordinary one. */
    private const val WEIGHT = 300f

    /** What a book scores when nothing is known about it. */
    private const val ORDINARY = 3.7f

    const val SHOWN = 15

    /** The catalogue's name for a genre, or null when it has none worth asking for. */
    fun subjectFor(genre: BookGenre): String? = when (genre) {
        BookGenre.FICTION -> "fiction"
        BookGenre.SCIENCE_FICTION -> "science fiction"
        BookGenre.FANTASY -> "fantasy"
        BookGenre.MYSTERY_THRILLER -> "mystery"
        BookGenre.ROMANCE -> "romance"
        BookGenre.POETRY -> "poetry"
        BookGenre.MEMOIR_BIOGRAPHY -> "biography"
        BookGenre.HISTORY -> "history"
        BookGenre.SCIENCE -> "science"
        BookGenre.PHILOSOPHY -> "philosophy"
        BookGenre.SELF_HELP -> "self-help"
        BookGenre.NONFICTION -> "nonfiction"
        BookGenre.OTHER -> null
    }

    /** Every genre that can be asked for, the reader's own most-read ones first. */
    fun genresInOrder(mostRead: List<BookGenre>): List<BookGenre> {
        val askable = BookGenre.entries.filter { subjectFor(it) != null }
        return (mostRead.filter { it in askable } + askable).distinct()
    }

    /** The works in a search reply that carry a rating. Empty for anything unreadable. */
    fun parse(json: String): List<Pick> = runCatching {
        val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
        (0 until docs.length()).mapNotNull { i ->
            val doc = docs.optJSONObject(i) ?: return@mapNotNull null
            val title = doc.optString("title").trim()
            val key = doc.optString("key").trim()
            val rating = doc.optDouble("ratings_average", Double.NaN)
            val count = doc.optInt("ratings_count", 0)
            if (title.isEmpty() || key.isEmpty() || rating.isNaN() || count <= 0) return@mapNotNull null
            Pick(
                key = key,
                title = title,
                author = doc.optJSONArray("author_name")?.optString(0)?.trim()?.takeIf { it.isNotEmpty() },
                year = doc.optInt("first_publish_year", 0).takeIf { it > 0 },
                rating = rating.toFloat().coerceIn(1f, 5f),
                ratings = count,
            )
        }
    }.getOrDefault(emptyList())

    /** The average, pulled toward [ORDINARY] by how few ratings stand behind it. */
    fun score(pick: Pick): Float =
        (pick.ratings / (pick.ratings + WEIGHT)) * pick.rating + (WEIGHT / (pick.ratings + WEIGHT)) * ORDINARY

    /**
     * The list the reader sees: enough ratings to mean something, nothing they
     * already have, one entry per title, best first.
     */
    fun rank(picks: List<Pick>, ownedTitles: Collection<String>): List<Pick> {
        val owned = ownedTitles.map(::titleKey).filter { it.isNotEmpty() }.toSet()
        return picks
            .filter { it.ratings >= MIN_RATINGS }
            .filter { titleKey(it.title) !in owned }
            // Best first, then one per title, so the copy kept is the best-rated one.
            .sortedByDescending(::score)
            .distinctBy { titleKey(it.title) }
            .take(SHOWN)
    }

    /** A title reduced to what makes it the same book: no case, punctuation, subtitle or leading article. */
    fun titleKey(title: String): String =
        title.substringBefore(':').lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
            .trim()
            .replace(Regex("^(the|a|an) "), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}

package com.pagetime.app.data.shelf

import org.json.JSONObject

/** One candidate author from a name search. */
data class AuthorCandidate(
    /** Open Library key, e.g. "OL21594A". */
    val key: String,
    val name: String,
    val workCount: Int,
    val topWork: String?,
    val alternateNames: List<String>,
)

/** One work by an author, as the shelf needs it. */
data class AuthorWork(
    val title: String,
    val firstPublishedYear: Int?,
    /** Open Library work key, kept so a slot id is stable across renames. */
    val workKey: String?,
)

/** What a name lookup concluded. */
sealed interface AuthorLookup {
    data class Found(val author: AuthorCandidate) : AuthorLookup

    /**
     * Several people share this name and none stands out.
     *
     * Kept as its own answer rather than picking the biggest, because showing
     * one person's bibliography under another's name is a confident lie and
     * the reader has no way to catch it.
     */
    data class Ambiguous(val candidates: List<AuthorCandidate>) : AuthorLookup

    data object NotFound : AuthorLookup
}

/**
 * Turning an author's name into the list of things they wrote.
 *
 * WHY A DATABASE AND NOT A LANGUAGE MODEL
 *
 * The obvious build is to ask Gemini "list every book this author published".
 * It is close to the worst available use of a model. A bibliography is a set
 * of factual claims about what exists, models recall those unreliably, and
 * nothing in the output separates the titles it knows from the ones it
 * assembled. The reader gets four real novels and a fifth that was never
 * written, formatted identically, and goes hunting the internet for an EPUB
 * of a book that does not exist.
 *
 * Open Library returns rows. It can be incomplete; it cannot invent.
 *
 * It was in this app before and was removed as a BOOK source, because its
 * EPUBs are OCR'd photographs of paper — running heads mid-sentence, words
 * broken across scan lines. That is a text-quality problem and it says
 * nothing about the catalogue, which was always good. The right thing was
 * thrown out with the wrong one.
 *
 * WHERE A MODEL WOULD EARN ITS PLACE
 *
 * Not "what did they write" but "which of these should I read next, and why"
 * — judgement over a list that has already been retrieved. Retrieve, then
 * reason; never let the model be the database.
 */
object AuthorBibliography {

    /**
     * How much bigger the leading candidate must be before it is taken as
     * THE author rather than one of several people with a name.
     *
     * Names collide, and two different writers called J. Smith are not a
     * quirk of the data — they are two different people. When the top result
     * is not clearly the one being asked about, saying so beats guessing.
     */
    const val DOMINANCE = 3

    /** Below this a "match" on a shared surname means very little. */
    private const val MIN_NAME_MATCH_LENGTH = 3

    /**
     * Picks the author, or declines to.
     *
     * [wanted] is the name as the reader's library spells it, which is not
     * necessarily how Open Library spells it — the same transliteration
     * problem that would have hidden two Dostoevsky novels from the ladder.
     * So alternate names count, and the surname is compared with the same
     * key the catalogue matcher uses.
     */
    fun chooseAuthor(wanted: String, candidates: List<AuthorCandidate>): AuthorLookup {
        val key = ShelfMatcher.surnameKey(wanted)
        if (key.isNullOrBlank() || key.length < MIN_NAME_MATCH_LENGTH) return AuthorLookup.NotFound

        val plausible = candidates.filter { candidate ->
            val names = (listOf(candidate.name) + candidate.alternateNames)
            names.any { ShelfMatcher.normalize(it).split(' ').contains(key) }
        }
        if (plausible.isEmpty()) return AuthorLookup.NotFound

        // An exact match on the whole name settles it outright: "George Eliot"
        // is George Eliot even if some other record has more works attached.
        val wantedFull = ShelfMatcher.normalize(wanted)
        plausible.filter { ShelfMatcher.normalize(it.name) == wantedFull }
            .maxByOrNull { it.workCount }
            ?.let { return AuthorLookup.Found(it) }

        val ranked = plausible.sortedByDescending { it.workCount }
        val top = ranked.first()
        val runnerUp = ranked.getOrNull(1)
        // One record with nothing attached to it is not an author, it is a
        // stub — and a bibliography of zero books helps nobody.
        if (top.workCount <= 0) return AuthorLookup.NotFound
        if (runnerUp == null || top.workCount >= runnerUp.workCount * DOMINANCE) {
            return AuthorLookup.Found(top)
        }
        return AuthorLookup.Ambiguous(ranked.take(5))
    }

    // --- Parsing ---
    //
    // Written to tolerate missing fields throughout. This runs against a live
    // third-party API whose exact response shape could not be checked from the
    // build environment, so anything absent has to degrade to "unknown" rather
    // than throw.

    fun parseAuthorSearch(body: String): List<AuthorCandidate> {
        val docs = runCatching { JSONObject(body).optJSONArray("docs") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AuthorCandidate>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val key = doc.optString("key").takeIf { it.isNotBlank() } ?: continue
            val name = doc.optString("name").takeIf { it.isNotBlank() } ?: continue
            val alternates = doc.optJSONArray("alternate_names")
            out += AuthorCandidate(
                key = key.substringAfterLast('/'),
                name = name,
                workCount = doc.optInt("work_count", 0),
                topWork = doc.optString("top_work").takeIf { it.isNotBlank() },
                alternateNames = buildList {
                    if (alternates != null) {
                        for (j in 0 until alternates.length()) {
                            alternates.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                },
            )
        }
        return out
    }

    /**
     * The works, cleaned up.
     *
     * Open Library carries duplicates, editions filed as works, and entries
     * that are a translation or a collected edition of something already in
     * the list. Left raw, an author shelf reads as forty rows of which twelve
     * are the same novel — which teaches the reader the list is junk even
     * where it is accurate.
     */
    fun parseWorks(body: String): List<AuthorWork> {
        val entries = runCatching { JSONObject(body).optJSONArray("entries") }.getOrNull()
            ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<AuthorWork>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val title = entry.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
            val normalized = ShelfMatcher.normalize(title)
            if (normalized.isBlank() || !seen.add(normalized)) continue
            out += AuthorWork(
                title = title,
                firstPublishedYear = yearFrom(entry.optString("first_publish_date")),
                workKey = entry.optString("key").takeIf { it.isNotBlank() }?.substringAfterLast('/'),
            )
        }
        return out
    }

    /**
     * A four-digit year out of whatever the date field holds.
     *
     * Open Library's dates are free text: "1871", "June 1871", "1871-12-01",
     * "18??" all appear. A parser that expected one shape would drop most of
     * them, and the year is the only part a shelf shows.
     */
    internal fun yearFrom(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""\b(1[0-9]{3}|20[0-9]{2})\b""").find(raw) ?: return null
        return match.value.toIntOrNull()
    }

    /** Newest first is wrong for an author; a career reads forwards. */
    fun inCareerOrder(works: List<AuthorWork>): List<AuthorWork> =
        works.sortedWith(
            compareBy(
                // Undated works go last rather than pretending to be ancient.
                { it.firstPublishedYear ?: Int.MAX_VALUE },
                { it.title.lowercase() },
            )
        )
}

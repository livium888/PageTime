package com.pagetime.app.data.learning

import com.pagetime.app.data.local.ConceptEntity

/**
 * Local, zero-API-call concept highlight matcher.
 *
 * Given a list of concepts (each with a space-separated [ConceptEntity.keywords]
 * field) and a block of visible page text, finds which concepts are mentioned
 * on the page and at which character offsets.
 *
 * This powers the "ambient intelligence" feature: as you read, words that
 * connect to previously explained concepts get a subtle highlight. The entire
 * operation is a local string search — no Gemini call, no network, no quota.
 */
object ConceptHighlightMatcher {

    data class Hit(
        val conceptId: String,
        val conceptLabel: String,
        /** Start offset of the matched keyword within [pageText]. */
        val startOffset: Int,
        /** Length of the matched keyword. */
        val length: Int
    )

    /**
     * Finds concept keyword occurrences in [pageText].
     *
     * Returns at most [maxHits] hits, sorted by position in the text.
     * Longer keywords are preferred over shorter ones to avoid partial matches
     * (e.g. "republic" should not match if "republican" is a separate keyword).
     *
     * @param concepts All known concepts for the current book.
     * @param pageText The visible text of the current reader page.
     * @param maxHits Maximum number of highlight hits to return.
     */
    fun findHighlights(
        concepts: List<ConceptEntity>,
        pageText: String,
        maxHits: Int = 8
    ): List<Hit> {
        if (pageText.isBlank() || concepts.isEmpty()) return emptyList()

        val lowerPage = pageText.lowercase()
        val hits = mutableListOf<Hit>()

        // Build a list of (keyword, conceptId, conceptLabel) sorted by keyword
        // length descending so longer keywords are matched first (avoids
        // "republican" masking "republic").
        data class KeywordEntry(val keyword: String, val conceptId: String, val label: String)

        val entries = concepts.flatMap { concept ->
            val kws = concept.keywords
            if (kws.isBlank()) return@flatMap emptyList()
            kws.split(" ")
                .filter { it.length >= 3 }
                .distinct()
                .map { kw -> KeywordEntry(kw, concept.id, concept.label) }
        }.sortedByDescending { it.keyword.length }

        // Track which (conceptId, keyword) pairs we've already matched so we
        // don't produce duplicate hits for the same concept.
        val matched = mutableSetOf<String>()

        for (entry in entries) {
            val kw = entry.keyword
            val lowerKw = kw.lowercase()
            var searchFrom = 0

            while (searchFrom < lowerPage.length) {
                val idx = lowerPage.indexOf(lowerKw, searchFrom)
                if (idx < 0) break

                // Word-boundary check: the character before and after the match
                // must not be a letter or digit, so "republic" doesn't match
                // inside "republican".
                val beforeOk = idx == 0 || !lowerPage[idx - 1].isLetterOrDigit()
                val afterIdx = idx + kw.length
                val afterOk = afterIdx >= lowerPage.length || !lowerPage[afterIdx].isLetterOrDigit()

                if (beforeOk && afterOk) {
                    val key = "${entry.conceptId}:${kw}"
                    if (key !in matched) {
                        matched += key
                        hits += Hit(
                            conceptId = entry.conceptId,
                            conceptLabel = entry.label,
                            startOffset = idx,
                            length = kw.length
                        )
                    }
                    // Only one hit per keyword per concept.
                    break
                }

                searchFrom = idx + 1
            }

            if (hits.size >= maxHits) break
        }

        return hits.sortedBy { it.startOffset }.take(maxHits)
    }
}

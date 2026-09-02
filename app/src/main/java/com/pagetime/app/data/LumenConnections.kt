package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/** A locally selected candidate; no text leaves the device during this step. */
data class LumenCandidate(
    val card: LumenCardEntity,
    val score: Int,
    val reasons: List<String>
)

/**
 * Selects a small, explainable shortlist before any optional AI request.
 * This intentionally uses no embeddings, network, or database-wide prompt.
 */
object LumenConnections {
    fun rank(
        newCard: LumenCardEntity,
        cards: List<LumenCardEntity>,
        limit: Int = 12
    ): List<LumenCandidate> {
        val sourceTerms = terms(newCard.front + " " + newCard.back + " " + newCard.quote)
        return cards.asSequence()
            .filter { it.id != newCard.id }
            .map { candidate ->
                val candidateTerms = terms(candidate.front + " " + candidate.back + " " + candidate.quote)
                val overlap = sourceTerms.intersect(candidateTerms).size
                val sameBook = newCard.bookId.isNotBlank() && newCard.bookId == candidate.bookId
                val sameBox = newCard.box == candidate.box
                val linked = LumenCapture.linksFromJson(newCard.linksJson).contains(candidate.id) ||
                    LumenCapture.linksFromJson(candidate.linksJson).contains(newCard.id)
                val score = overlap * 4 + if (sameBook) 3 else 0 + if (sameBox) 1 else 0 + if (linked) 2 else 0
                val reasons = buildList {
                    if (overlap > 0) add("$overlap shared term${if (overlap == 1) "" else "s"}")
                    if (sameBook) add("same source")
                    if (sameBox) add("same box")
                    if (linked) add("already linked")
                }
                LumenCandidate(candidate, score, reasons)
            }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<LumenCandidate> { it.score }.thenBy { it.card.indexNumber })
            .take(limit.coerceIn(1, 50))
            .toList()
    }

    /**
     * Filing suggestions for a freshly captured draft: where should this new
     * slip continue the line? Ranked locally against the whole box, then
     * limited to the target [box] (capture files into box 1 by default). The
     * reader shows these as "File behind…" options before saving.
     */
    fun filingCandidates(
        cards: List<LumenCardEntity>,
        front: String,
        back: String,
        quote: String,
        bookId: String,
        box: Int = 1,
        limit: Int = 5
    ): List<LumenCardEntity> {
        val pseudo = LumenCardEntity(
            id = "__draft__",
            bookId = bookId,
            box = box,
            indexNumber = "",
            front = front,
            back = back,
            quote = quote,
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            createdAt = 0,
            updatedAt = 0
        )
        return rank(pseudo, cards, limit = 50)
            .filter { it.card.box == box }
            // Pure same-box proximity is not a filing reason — the new slip
            // should continue the thought, not just share the box.
            .filter { candidate ->
                candidate.reasons.any {
                    it.startsWith("shared term") || it == "same source" || it == "already linked"
                }
            }
            .sortedWith(
                compareByDescending<LumenCandidate> { it.score }
                    .thenBy(LumenAddress.COMPARATOR) { it.card.indexNumber }
            )
            .take(limit.coerceIn(0, 20))
            .map { it.card }
    }

    private fun terms(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.length >= 4 }
        .toSet()
}

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

    private fun terms(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.length >= 4 }
        .toSet()
}

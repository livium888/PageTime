package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.local.ConceptEntity

/** A safe, exact text anchor for one EPUB concept marker. */
data class EpubConceptAnchor(
    val conceptId: String,
    val label: String,
    val phrase: String,
    val before: String,
    val after: String
)

/**
 * Chooses a small number of meaningful concept phrases for the current EPUB resource.
 * It is deliberately conservative: no exact phrase means no marker.
 */
object EpubConceptHints {
    fun anchors(
        concepts: List<ConceptEntity>,
        chapterIndex: Int,
        level: String,
        contextCharacters: Int = 72
    ): List<EpubConceptAnchor> {
        val normalizedLevel = level.takeIf { it in LEVELS } ?: "subtle"
        if (normalizedLevel == "off") return emptyList()
        val minimumConfidence = if (normalizedLevel == "active") 0.55f else 0.65f
        val limit = if (normalizedLevel == "active") 6 else 3

        // First pass: find anchors by matching label inside source quote.
        val labelAnchors = concepts
            .asSequence()
            .filter { it.lastChapterIndex == chapterIndex }
            .filter { it.confidence >= minimumConfidence }
            .filter { it.label.length >= 4 && it.sourceQuote?.isNotBlank() == true }
            .filterNot { it.label.lowercase() in GENERIC_LABELS }
            .mapNotNull { concept ->
                val quote = concept.sourceQuote ?: return@mapNotNull null
                val start = quote.indexOf(concept.label, ignoreCase = true)
                if (start < 0) return@mapNotNull null
                val end = start + concept.label.length
                EpubConceptAnchor(
                    conceptId = concept.id,
                    label = concept.label,
                    phrase = quote.substring(start, end),
                    before = quote.substring((start - contextCharacters).coerceAtLeast(0), start),
                    after = quote.substring(end, (end + contextCharacters).coerceAtMost(quote.length))
                )
            }
            .sortedWith(compareByDescending<EpubConceptAnchor> { anchor ->
                concepts.firstOrNull { it.id == anchor.conceptId }?.confidence ?: 0f
            }.thenBy { it.label.lowercase() })
            .distinctBy { it.phrase.lowercase() }
            .take(limit)
            .toList()

        // Second pass: for concepts that didn't get a label-in-quote anchor,
        // try to find the longest keyword from their keywords field inside the
        // source quote. This catches concepts where the label itself doesn't
        // appear verbatim but a significant keyword does.
        val matchedConceptIds = labelAnchors.map { it.conceptId }.toSet()
        val keywordAnchors = concepts
            .asSequence()
            .filter { it.id !in matchedConceptIds }
            .filter { it.lastChapterIndex == chapterIndex }
            .filter { it.confidence >= minimumConfidence }
            .filter { it.sourceQuote?.isNotBlank() == true && it.keywords.isNotBlank() }
            .sortedByDescending { it.confidence }
            .mapNotNull { concept ->
                val quote = concept.sourceQuote ?: return@mapNotNull null
                // Find the longest keyword that appears in the quote.
                val bestKeyword = concept.keywords.split(" ")
                    .filter { it.length >= 4 }
                    .sortedByDescending { it.length }
                    .firstOrNull { kw ->
                        quote.indexOf(kw, ignoreCase = true) >= 0
                    }
                    ?: return@mapNotNull null
                val start = quote.indexOf(bestKeyword, ignoreCase = true)
                val end = start + bestKeyword.length
                EpubConceptAnchor(
                    conceptId = concept.id,
                    label = concept.label,
                    phrase = quote.substring(start, end),
                    before = quote.substring((start - contextCharacters).coerceAtLeast(0), start),
                    after = quote.substring(end, (end + contextCharacters).coerceAtMost(quote.length))
                )
            }
            .take(limit - labelAnchors.size)
            .toList()

        return (labelAnchors + keywordAnchors).take(limit)
    }

    private val LEVELS = setOf("off", "subtle", "active")
    private val GENERIC_LABELS = setOf(
        "idea", "concept", "thing", "people", "person", "place", "event", "chapter"
    )
}

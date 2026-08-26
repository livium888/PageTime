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

        return concepts
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
    }

    private val LEVELS = setOf("off", "subtle", "active")
    private val GENERIC_LABELS = setOf(
        "idea", "concept", "thing", "people", "person", "place", "event", "chapter"
    )
}

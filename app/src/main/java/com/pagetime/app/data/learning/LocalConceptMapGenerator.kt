package com.pagetime.app.data.learning

/**
 * Conservative offline fallback: extracts sentence-sized ideas and connects adjacent
 * ideas with a deliberately honest "related to" edge rather than inventing causality.
 */
object LocalConceptMapGenerator {
    fun generate(context: LearningContext, limit: Int = 8): ConceptMapGenerationResult {
        val sentences = context.recentText
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter { it.length in 55..260 && it.split(" ").size >= 9 }
            .distinctBy { it.lowercase() }
            .take(limit.coerceAtLeast(2))

        val concepts = sentences.mapIndexed { index, sentence ->
            val words = sentence.split(" ")
                .map { it.trim(',', '.', ';', ':', '!', '?', '"', '\'') }
                .filter { it.length >= 6 && it.all(Char::isLetter) }
            val label = words
                .sortedByDescending { it.length }
                .firstOrNull()
                ?.replaceFirstChar { it.uppercase() }
                ?: "Idea ${index + 1}"
            GeneratedConcept(
                label = label,
                description = sentence,
                type = "idea",
                sourceQuote = sentence,
                confidence = 0.65f
            )
        }
        val relationships = concepts.zipWithNext().map { (source, target) ->
            GeneratedConceptRelationship(
                sourceLabel = source.label,
                targetLabel = target.label,
                relationType = "related to",
                explanation = "These ideas appear together in the reading context.",
                sourceQuote = source.sourceQuote,
                confidence = 0.55f
            )
        }
        return ConceptMapGenerationResult(concepts, relationships)
    }
}

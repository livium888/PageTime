package com.pagetime.app.data.learning

data class GeneratedConcept(
    val label: String,
    val description: String,
    val type: String,
    val sourceQuote: String,
    val confidence: Float
)

data class GeneratedConceptRelationship(
    val sourceLabel: String,
    val targetLabel: String,
    val relationType: String,
    val explanation: String,
    val sourceQuote: String,
    val confidence: Float
)

data class ConceptMapGenerationResult(
    val concepts: List<GeneratedConcept>,
    val relationships: List<GeneratedConceptRelationship>
)

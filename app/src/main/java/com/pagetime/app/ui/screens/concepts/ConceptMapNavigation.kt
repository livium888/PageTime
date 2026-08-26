package com.pagetime.app.ui.screens.concepts

import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipEntity

/** A relationship viewed from one selected concept to its neighbour. */
data class RelatedConcept(
    val concept: ConceptEntity,
    val relationship: ConceptRelationshipEntity,
    val isForward: Boolean
)

/**
 * Presentation rules for the guided concept-map explorer.
 *
 * The database intentionally stores raw observations. This layer gives the reader a
 * stable starting point and keeps navigation about graph structure rather than the
 * order in which Room happened to emit rows.
 */
object ConceptMapNavigation {
    fun orderedConcepts(map: ConceptMap): List<ConceptEntity> {
        val degree = mutableMapOf<String, Int>()
        map.relationships.forEach { relationship ->
            degree[relationship.sourceConceptId] = (degree[relationship.sourceConceptId] ?: 0) + 1
            degree[relationship.targetConceptId] = (degree[relationship.targetConceptId] ?: 0) + 1
        }
        return map.concepts.sortedWith(
            compareByDescending<ConceptEntity> { degree[it.id] ?: 0 }
                .thenByDescending { it.confidence }
                .thenByDescending { it.mentionCount }
                .thenBy { it.firstChapterIndex }
                .thenBy { it.label.lowercase() }
        )
    }

    fun relatedConcepts(map: ConceptMap, conceptId: String): List<RelatedConcept> {
        val conceptsById = map.concepts.associateBy { it.id }
        return map.relationships
            .mapNotNull { relationship ->
                when (conceptId) {
                    relationship.sourceConceptId -> conceptsById[relationship.targetConceptId]?.let {
                        RelatedConcept(it, relationship, isForward = true)
                    }
                    relationship.targetConceptId -> conceptsById[relationship.sourceConceptId]?.let {
                        RelatedConcept(it, relationship, isForward = false)
                    }
                    else -> null
                }
            }
            .distinctBy { it.concept.id }
            .sortedWith(
                compareByDescending<RelatedConcept> { it.relationship.confidence }
                    .thenBy { it.concept.firstChapterIndex }
                    .thenBy { it.concept.label.lowercase() }
            )
    }

    fun connectionCount(map: ConceptMap, conceptId: String): Int =
        map.relationships.count {
            it.sourceConceptId == conceptId || it.targetConceptId == conceptId
        }
}

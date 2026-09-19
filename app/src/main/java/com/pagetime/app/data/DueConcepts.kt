package com.pagetime.app.data

import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ExplanationEntity

/**
 * Picks the next concept to explain across every book, and counts how many
 * have never been explained at all — the cross-book equivalent of a due
 * flashcard queue.
 *
 * Explain-back has no spaced-repetition schedule of its own: within one book,
 * [ExplainBackRepository.conceptsForRange] just rotates through that
 * chapter's concepts, never-explained ones first, then the ones explained
 * longest ago. This is the same ordering extended across every book, so
 * "due" here means something simpler and permanent than a flashcard's due
 * date — a concept nobody has ever answered for yet, or the one waiting
 * longest, not one whose review interval has elapsed.
 */
object DueConcepts {

    /**
     * The single most overdue concept, or null when the reader has no
     * concepts anywhere yet.
     */
    fun next(concepts: List<ConceptEntity>, explanations: List<ExplanationEntity>): ConceptEntity? {
        if (concepts.isEmpty()) return null
        val lastExplainedAt = lastExplainedAtByConcept(explanations)
        return concepts.minWithOrNull(
            compareBy(
                { key(it) in lastExplainedAt },
                { lastExplainedAt[key(it)] ?: 0L }
            )
        )
    }

    /** How many concepts, across every book, have never been explained even once. */
    fun unexplainedCount(concepts: List<ConceptEntity>, explanations: List<ExplanationEntity>): Int {
        if (concepts.isEmpty()) return 0
        val explained = explanations.map { it.bookId to it.conceptLabel.trim().lowercase() }.toSet()
        return concepts.count { key(it) !in explained }
    }

    private fun key(concept: ConceptEntity): Pair<String, String> =
        concept.bookId to concept.label.trim().lowercase()

    private fun lastExplainedAtByConcept(explanations: List<ExplanationEntity>): Map<Pair<String, String>, Long> =
        explanations
            .groupBy { it.bookId to it.conceptLabel.trim().lowercase() }
            .mapValues { (_, rows) -> rows.maxOf { it.createdAt } }
}

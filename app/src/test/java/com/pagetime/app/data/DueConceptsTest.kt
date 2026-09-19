package com.pagetime.app.data

import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ExplanationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DueConceptsTest {

    private fun concept(bookId: String, label: String) = ConceptEntity(
        id = "$bookId-$label",
        bookId = bookId,
        label = label,
        normalizedLabel = label.lowercase(),
        description = "",
        type = "idea",
        firstChapterIndex = 2,
        lastChapterIndex = 2,
        sourceQuote = null,
        confidence = 1f,
        mentionCount = 1,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun explanation(bookId: String, label: String, createdAt: Long) = ExplanationEntity(
        id = "$bookId-$label-$createdAt",
        bookId = bookId,
        chapterIndex = 2,
        chapterTitle = null,
        conceptLabel = label,
        conceptKeyPoints = "",
        userExplanation = "an answer",
        aiFeedback = null,
        accuracyScore = null,
        completenessScore = null,
        clarityScore = null,
        overallScore = 4f,
        whatTheyGotRight = null,
        whatTheyMissed = null,
        suggestedImprovement = null,
        simplerVersion = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `no concepts anywhere means nothing is due`() {
        assertNull(DueConcepts.next(emptyList(), emptyList()))
        assertEquals(0, DueConcepts.unexplainedCount(emptyList(), emptyList()))
    }

    @Test
    fun `a never-explained concept in book B beats an older explained one in book A`() {
        val concepts = listOf(concept("A", "gravity"), concept("B", "inertia"))
        val explanations = listOf(explanation("A", "gravity", createdAt = 100L))

        val next = DueConcepts.next(concepts, explanations)

        assertEquals("B", next?.bookId)
        assertEquals("inertia", next?.label)
    }

    @Test
    fun `once everything has been explained, the oldest explanation comes up again`() {
        val concepts = listOf(concept("A", "gravity"), concept("B", "inertia"))
        val explanations = listOf(
            explanation("A", "gravity", createdAt = 500L),
            explanation("B", "inertia", createdAt = 100L),
        )

        val next = DueConcepts.next(concepts, explanations)

        assertEquals("B", next?.bookId)
        assertEquals("inertia", next?.label)
    }

    @Test
    fun `unexplained count is per book and label, not a raw row count`() {
        val concepts = listOf(
            concept("A", "gravity"),
            concept("A", "inertia"),
            concept("B", "gravity"),
        )
        // Explaining "gravity" in book A does not count as explaining the
        // distinct "gravity" concept that happens to live in book B too.
        val explanations = listOf(explanation("A", "gravity", createdAt = 100L))

        assertEquals(2, DueConcepts.unexplainedCount(concepts, explanations))
    }

    @Test
    fun `an explained concept still comes up again for reinforcement, just not as unexplained`() {
        val concepts = listOf(concept("A", "gravity"))
        val explanations = listOf(
            explanation("A", "gravity", createdAt = 100L),
            explanation("A", "gravity", createdAt = 900L),
        )

        assertEquals(0, DueConcepts.unexplainedCount(concepts, explanations))
        // Not gone from the queue — just no longer counted as "never explained".
        assertEquals("gravity", DueConcepts.next(concepts, explanations)?.label)
    }
}

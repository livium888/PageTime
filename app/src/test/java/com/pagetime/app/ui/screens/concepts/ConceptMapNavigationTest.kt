package com.pagetime.app.ui.screens.concepts

import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConceptMapNavigationTest {
    private val alpha = concept("a", "Alpha", mentions = 1, chapter = 2)
    private val beta = concept("b", "Beta", mentions = 2, chapter = 1)
    private val gamma = concept("c", "Gamma", mentions = 1, chapter = 3)
    private val map = ConceptMap(
        concepts = listOf(gamma, alpha, beta),
        relationships = listOf(
            relationship("ab", "a", "b", confidence = 0.7f, chapter = 2),
            relationship("bc", "b", "c", confidence = 0.9f, chapter = 3)
        )
    )

    @Test
    fun `most connected concept is the stable starting point`() {
        val ordered = ConceptMapNavigation.orderedConcepts(map)

        assertEquals("Beta", ordered.first().label)
        assertEquals(listOf("Beta", "Alpha", "Gamma"), ordered.map { it.label })
    }

    @Test
    fun `related concepts preserve edge direction`() {
        val fromBeta = ConceptMapNavigation.relatedConcepts(map, "b")

        assertEquals(listOf("Gamma", "Alpha"), fromBeta.map { it.concept.label })
        assertTrue(fromBeta.first().isForward)
        assertTrue(!fromBeta.last().isForward)
        assertEquals("bc", fromBeta.first().relationship.id)
    }

    @Test
    fun `unknown concept has no neighbours`() {
        assertTrue(ConceptMapNavigation.relatedConcepts(map, "missing").isEmpty())
        assertEquals(0, ConceptMapNavigation.connectionCount(map, "missing"))
    }

    private fun concept(id: String, label: String, mentions: Int, chapter: Int) = ConceptEntity(
        id = id,
        bookId = "book",
        label = label,
        normalizedLabel = label.lowercase(),
        description = "Description of $label",
        type = "idea",
        firstChapterIndex = chapter,
        lastChapterIndex = chapter,
        sourceQuote = null,
        confidence = 0.8f,
        mentionCount = mentions,
        createdAt = chapter.toLong(),
        updatedAt = chapter.toLong()
    )

    private fun relationship(
        id: String,
        source: String,
        target: String,
        confidence: Float,
        chapter: Int
    ) = ConceptRelationshipEntity(
        id = id,
        bookId = "book",
        sourceConceptId = source,
        targetConceptId = target,
        relationType = "supports",
        explanation = "One idea supports the other.",
        sourceQuote = null,
        confidence = confidence,
        firstChapterIndex = chapter,
        createdAt = chapter.toLong(),
        updatedAt = chapter.toLong()
    )
}

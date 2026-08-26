package com.pagetime.app.data

import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LocalConceptMapGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConceptMapGeneratorTest {

    private fun context(text: String) = LearningContext(
        bookId = "book",
        bookTitle = "Book",
        chapterIndex = 1,
        chapterTitle = "Chapter",
        recentText = text,
        sourceFormat = "txt"
    )

    @Test
    fun `extracts concepts with meaningful labels`() {
        val ctx = context(
            "Photosynthesis is the process by which plants convert sunlight into chemical energy. " +
                "This mechanism depends on chlorophyll molecules in the leaf. " +
                "The reaction produces glucose and oxygen as byproducts."
        )
        val result = LocalConceptMapGenerator.generate(ctx)
        assertTrue("Should extract at least 2 concepts", result.concepts.size >= 2)
        // Labels should not be trivial words.
        val labels = result.concepts.map { it.label.lowercase() }
        assertFalse("Labels should not be empty strings", labels.any { it.isBlank() })
        assertFalse("Labels should not be 'the' or 'a'", labels.any { it in setOf("the", "a", "an", "and") })
    }

    @Test
    fun `concepts have typed relationship signals`() {
        val ctx = context(
            "The Industrial Revolution caused widespread urbanization across Europe. " +
                "However, it also led to severe pollution and poor working conditions. " +
                "For example, London experienced devastating smog events. " +
                "The economic growth therefore transformed social structures fundamentally."
        )
        val result = LocalConceptMapGenerator.generate(ctx)
        assertTrue("Should have relationships", result.relationships.isNotEmpty())
        val types = result.relationships.map { it.relationType }.toSet()
        // Should detect at least some typed relationships (not all "related to").
        assertTrue("Should detect typed relationships: $types",
            types.any { it != "related to" })
    }

    @Test
    fun `contrast signal produces contrasts-with relationship`() {
        val ctx = context(
            "Classical economics emphasizes free markets and minimal government intervention. " +
                "However, Keynesian economics argues that government spending is essential during recessions. " +
                "The two schools differ fundamentally in their approach to economic stabilization."
        )
        val result = LocalConceptMapGenerator.generate(ctx)
        val contrastRels = result.relationships.filter { it.relationType == "contrasts with" }
        assertTrue("Should detect at least one contrast relationship", contrastRels.isNotEmpty())
    }

    @Test
    fun `cross-sentence linking connects non-adjacent concepts sharing keywords`() {
        val ctx = context(
            "Photosynthesis converts solar energy into chemical energy in plants. " +
                "Animals obtain energy by consuming food derived from plants. " +
                "The energy cycle sustains all life on Earth through continuous transformation. " +
                "Solar panels mimic photosynthesis to generate renewable electricity."
        )
        val result = LocalConceptMapGenerator.generate(ctx)
        // Sentence 1 and 4 both mention photosynthesis/energy — they should be linked.
        val labels = result.concepts.map { it.label }
        val hasCrossLink = result.relationships.any { rel ->
            val srcIdx = labels.indexOf(rel.sourceLabel)
            val tgtIdx = labels.indexOf(rel.targetLabel)
            // Non-adjacent (difference > 1).
            srcIdx >= 0 && tgtIdx >= 0 && kotlin.math.abs(srcIdx - tgtIdx) > 1
        }
        assertTrue("Should link non-adjacent concepts sharing keywords", hasCrossLink)
    }

    @Test
    fun `does not produce more relationships than concepts allow`() {
        val ctx = context(
            "Gravity attracts all objects with mass toward each other. " +
                "The force weakens with distance following an inverse square law. " +
                "Planetary orbits result from gravitational balance with centripetal acceleration."
        )
        val result = LocalConceptMapGenerator.generate(ctx)
        // At most 24 relationships per the limit in the generator.
        assertTrue("Relationship count should be bounded",
            result.relationships.size <= 24)
    }

    @Test
    fun `returns empty relationships for very short text`() {
        val ctx = context("Too short.")
        val result = LocalConceptMapGenerator.generate(ctx)
        assertTrue(result.relationships.isEmpty())
    }
}

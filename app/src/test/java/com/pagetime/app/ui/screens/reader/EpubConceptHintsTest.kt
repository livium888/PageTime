package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.local.ConceptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubConceptHintsTest {
    private fun concept(
        id: String,
        label: String,
        quote: String,
        chapter: Int = 2,
        confidence: Float = 0.8f
    ) = ConceptEntity(
        id = id,
        bookId = "book",
        label = label,
        normalizedLabel = label.lowercase(),
        description = "Important idea",
        type = "principle",
        firstChapterIndex = chapter,
        lastChapterIndex = chapter,
        sourceQuote = quote,
        confidence = confidence,
        mentionCount = 1,
        createdAt = 0,
        updatedAt = 0
    )

    @Test
    fun `subtle mode returns only exact current chapter phrases`() {
        val anchors = EpubConceptHints.anchors(
            concepts = listOf(
                concept("one", "incentives", "Good incentives change behavior."),
                concept("two", "power", "Power belongs to another chapter.", chapter = 1),
                concept("three", "missing", "This quote does not contain the label.")
            ),
            chapterIndex = 2,
            level = "subtle"
        )

        assertEquals(listOf("incentives"), anchors.map { it.phrase })
        assertTrue(anchors.single().before.contains("Good"))
    }

    @Test
    fun `subtle mode limits markers and off mode returns none`() {
        val concepts = (1..5).map { index ->
            concept("$index", "concept$index", "This passage contains concept$index clearly.")
        }

        assertEquals(3, EpubConceptHints.anchors(concepts, 2, "subtle").size)
        assertTrue(EpubConceptHints.anchors(concepts, 2, "off").isEmpty())
    }

    @Test
    fun `active mode permits lower confidence and more markers`() {
        val concepts = (1..6).map { index ->
            concept("$index", "concept$index", "This passage contains concept$index clearly.", confidence = 0.6f)
        }

        assertEquals(6, EpubConceptHints.anchors(concepts, 2, "active").size)
    }
}

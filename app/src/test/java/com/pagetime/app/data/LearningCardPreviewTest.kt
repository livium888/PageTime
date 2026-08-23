package com.pagetime.app.data

import com.pagetime.app.data.local.LearningCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningCardPreviewTest {
    @Test
    fun previewKeepsSourceAndMasteryInformation() {
        val card = LearningCardEntity(
            id = "card",
            bookId = "book",
            chapterIndex = 2,
            chapterTitle = "The turning point",
            topic = "Decision",
            prompt = "What changed?",
            answer = "The decision was made.",
            explanation = null,
            sourceLocator = "{locator}",
            sourceFraction = null,
            sourceQuote = "The choice mattered.",
            fsrsCardJson = "{}",
            createdAt = 0,
            updatedAt = 0,
            lastRating = LearningRating.GOOD.value,
            reviewCount = 2,
            generatedByAi = true,
            aiConfidence = 0.9f,
            generationKey = "test"
        )

        val preview = card.preview()
        assertEquals("The turning point", preview.chapterLabel)
        assertEquals("Proficient", preview.mastery)
        assertTrue(preview.hasSource)
    }
}

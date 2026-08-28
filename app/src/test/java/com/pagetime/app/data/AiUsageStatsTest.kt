package com.pagetime.app.data

import com.pagetime.app.data.local.AiAnalysisLevel
import com.pagetime.app.data.local.AiUsageEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiUsageStatsTest {
    @Test
    fun `light is the safe default and intensive increases frequency`() {
        assertEquals(AiAnalysisLevel.LIGHT, AiAnalysisLevel.fromKey(null))
        assertTrue(
            AiAnalysisLevel.INTENSIVE.intervalSeconds < AiAnalysisLevel.LIGHT.intervalSeconds
        )
    }

    @Test
    fun `stats separate cards concepts failures and input`() {
        val now = Instant.parse("2026-08-26T12:00:00Z")
        val today = now.atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() + 12 * 60 * 60 * 1000L
        val stats = AiUsageStats.from(
            listOf(
                AiUsageEntity(
                    bookId = "book",
                    operation = AiUsageRepository.OPERATION_CARDS,
                    model = "flash",
                    status = AiUsageRepository.STATUS_SUCCESS,
                    inputCharacters = 4_000,
                    outputItems = 3,
                    createdAt = today
                ),
                AiUsageEntity(
                    bookId = "book",
                    operation = AiUsageRepository.OPERATION_CONCEPTS,
                    model = "flash",
                    status = AiUsageRepository.STATUS_SUCCESS,
                    inputCharacters = 2_000,
                    outputItems = 5,
                    secondaryItems = 4,
                    createdAt = today
                ),
                AiUsageEntity(
                    bookId = "book",
                    operation = AiUsageRepository.OPERATION_REFORMAT,
                    model = "flash",
                    status = AiUsageRepository.STATUS_SUCCESS,
                    inputCharacters = 8_000,
                    outputItems = 7_500,
                    createdAt = today
                ),
                AiUsageEntity(
                    bookId = "book",
                    operation = AiUsageRepository.OPERATION_CARDS,
                    model = "flash",
                    status = AiUsageRepository.STATUS_FAILED,
                    inputCharacters = 1_000,
                    createdAt = today - 4 * 24 * 60 * 60 * 1000L
                )
            ),
            now = now
        )

        assertEquals(4, stats.totalCalls)
        assertEquals(3, stats.successfulCalls)
        assertEquals(1, stats.failedCalls)
        assertEquals(2, stats.cardCalls)
        assertEquals(1, stats.conceptCalls)
        assertEquals(1, stats.reformatCalls)
        assertEquals(3, stats.cardsGenerated)
        assertEquals(5, stats.conceptsFound)
        assertEquals(4, stats.relationshipsFound)
        assertEquals(15_000L, stats.inputCharacters)
        assertEquals(3, stats.todayCalls)
        assertEquals(14_000L, stats.todayInputCharacters)
        assertEquals(3, stats.todayCardsGenerated)
    }
}

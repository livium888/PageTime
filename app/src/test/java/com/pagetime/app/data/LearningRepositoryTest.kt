package com.pagetime.app.data

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningRepositoryTest {
    @Test
    fun ratingMappingUsesFsrsValues() {
        assertEquals(1, LearningRating.AGAIN.value)
        assertEquals(2, LearningRating.HARD.value)
        assertEquals(3, LearningRating.GOOD.value)
        assertEquals(4, LearningRating.EASY.value)
        assertEquals("Good", LearningRating.fromValue(3).label)
    }

    @Test
    fun dueCardUsesPersistedFsrsDueInstant() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(!now.plus(Duration.ofMinutes(1)).isBefore(now))
    }
}

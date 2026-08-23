package com.pagetime.app.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPolicyTest {
    @Test
    fun dueUsesInclusiveBoundary() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(LearningPolicy.isDue(now, now))
        assertTrue(LearningPolicy.isDue(now.minusSeconds(1), now))
        assertFalse(LearningPolicy.isDue(now.plusSeconds(1), now))
    }

    @Test
    fun masteryRequiresRepeatedSuccessfulRecall() {
        assertEquals("Not tested", LearningPolicy.mastery(0, null))
        assertEquals("Familiar", LearningPolicy.mastery(1, LearningRating.GOOD))
        assertEquals("Proficient", LearningPolicy.mastery(2, LearningRating.GOOD))
        assertEquals("Needs review", LearningPolicy.mastery(3, LearningRating.AGAIN))
        assertEquals("Mastered", LearningPolicy.mastery(4, LearningRating.EASY))
    }
}

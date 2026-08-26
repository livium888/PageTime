package com.pagetime.app.ui.screens.review

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTimingTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-26T10:00:00Z")

    @Test
    fun `same day review includes today and exact time`() {
        val result = formatNextReview(Instant.parse("2026-08-26T14:30:00Z"), now, zone)

        assertTrue(result.startsWith("today at "))
        assertTrue(result.contains(":30"))
    }

    @Test
    fun `next day review includes tomorrow and exact time`() {
        val result = formatNextReview(Instant.parse("2026-08-27T09:15:00Z"), now, zone)

        assertTrue(result.startsWith("tomorrow at "))
        assertTrue(result.contains(":15"))
    }

    @Test
    fun `later review includes a calendar date and time`() {
        val result = formatNextReview(Instant.parse("2026-09-02T16:45:00Z"), now, zone)

        assertTrue(result.contains("2026"))
        assertTrue(result.contains(":45"))
    }
}

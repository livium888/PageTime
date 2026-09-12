package com.pagetime.app.ui.screens.review

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
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

    @Test
    fun `sub-hour intervals show minutes`() {
        assertEquals("10m", formatIntervalShort(Duration.ofMinutes(10)))
        assertEquals("0m", formatIntervalShort(Duration.ZERO))
    }

    @Test
    fun `hours round up at the half hour`() {
        assertEquals("1h", formatIntervalShort(Duration.ofMinutes(60)))
        assertEquals("1h", formatIntervalShort(Duration.ofMinutes(89)))
        assertEquals("2h", formatIntervalShort(Duration.ofMinutes(90)))
        assertEquals("2h", formatIntervalShort(Duration.ofHours(2)))
    }

    @Test
    fun `days round at the half day`() {
        assertEquals("1d", formatIntervalShort(Duration.ofDays(1)))
        assertEquals("1d", formatIntervalShort(Duration.ofHours(35)))
        assertEquals("2d", formatIntervalShort(Duration.ofHours(36)))
        assertEquals("2d", formatIntervalShort(Duration.ofDays(2)))
    }

    @Test
    fun `months kick in past thirty days`() {
        assertEquals("30d", formatIntervalShort(Duration.ofDays(30).minusMinutes(1)))
        assertEquals("1mo", formatIntervalShort(Duration.ofDays(30)))
        assertEquals("2mo", formatIntervalShort(now.plus(Duration.ofDays(60)), now))
    }
}

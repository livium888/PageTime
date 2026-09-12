package com.pagetime.app.ui.screens.review

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Anki-style compact interval captions shown on the rating buttons.
 * Rounding nudges up at the half mark ("2.5d" reads as "3d"), matching how
 * Anki's own button captions read.
 */
class ReviewIntervalCaptionTest {

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
        val now = Instant.now()
        assertEquals("2mo", formatIntervalShort(now.plus(Duration.ofDays(60)), now))
    }
}

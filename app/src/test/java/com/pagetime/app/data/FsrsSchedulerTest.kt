package com.pagetime.app.data

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Scheduler
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsSchedulerTest {
    private val scheduler = Scheduler.builder()
        .enableFuzzing(false)
        .build()

    @Test
    fun newCardIsDueImmediatelyAndGoodSchedulesFutureReview() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val newCard = Card.builder().due(now).build()
        assertEquals(now, newCard.due)

        val reviewed = scheduler.reviewCard(newCard, LumenRating.GOOD.toFsrs(), now).card()
        assertTrue(reviewed.due.isAfter(now))
        assertTrue(Duration.between(now, reviewed.due).toSeconds() > 0)
    }

    @Test
    fun againDoesNotPretendTheReaderRemembered() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val newCard = Card.builder().due(now).build()
        val reviewed = scheduler.reviewCard(newCard, LumenRating.AGAIN.toFsrs(), now).card()

        assertTrue(reviewed.due.isAfter(now))
        assertTrue(Duration.between(now, reviewed.due).toMinutes() <= 10)
    }
}

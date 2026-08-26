package com.pagetime.app.data

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        val reviewed = scheduler.reviewCard(newCard, LearningRating.GOOD.toFsrs(), now).card()
        assertTrue(reviewed.due.isAfter(now))
        assertTrue(Duration.between(now, reviewed.due).toSeconds() > 0)
    }

    @Test
    fun persistedNewCardCanBeRatedWithEveryButton() {
        val now = Instant.parse("2026-01-01T12:00:00Z")

        LearningRating.entries.forEach { rating ->
            val newCard = Card.builder().due(now).build()
            val restored = FsrsCardCodec.fromJson(FsrsCardCodec.toJson(newCard))

            // Null stability and difficulty are meaningful: FSRS uses them to
            // identify a card's first review and initialize its scheduling state.
            assertNull(restored.stability)
            assertNull(restored.difficulty)

            val reviewed = scheduler.reviewCard(restored, rating.toFsrs(), now).card()
            assertNotNull("${rating.label} should produce a due date", reviewed.due)
            assertTrue(reviewed.due.isAfter(now))
        }
    }

    @Test
    fun oldZeroValueCardIsNormalizedToNewCardBeforeReview() {
        val json = """
            {"state":"LEARNING","step":0,"stability":0.0,"difficulty":0.0,"due":0,"lastReview":null}
        """.trimIndent()

        val restored = FsrsCardCodec.fromJson(json)
        assertEquals(State.LEARNING, restored.state)
        assertNull(restored.stability)
        assertNull(restored.difficulty)

        val now = Instant.parse("2026-01-01T12:00:00Z")
        val reviewed = scheduler.reviewCard(restored, LearningRating.HARD.toFsrs(), now).card()
        assertTrue(reviewed.due.isAfter(now))
    }

    @Test
    fun againDoesNotPretendTheReaderRemembered() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val newCard = Card.builder().due(now).build()
        val reviewed = scheduler.reviewCard(newCard, LearningRating.AGAIN.toFsrs(), now).card()

        assertTrue(reviewed.due.isAfter(now))
        assertTrue(Duration.between(now, reviewed.due).toMinutes() <= 10)
    }
}

package com.pagetime.app.data

import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsCardCodecTest {
    private val scheduler = Scheduler.builder()
        .enableFuzzing(false)
        .build()

    @Test
    fun newCardRoundTripKeepsFsrsNewCardSemantics() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val decoded = FsrsCardCodec.fromJson(
            FsrsCardCodec.toJson(
                io.github.openspacedrepetition.Card.builder()
                    .due(now)
                    .build()
            )
        )

        assertEquals(State.LEARNING, decoded.state)
        assertNull(decoded.stability)
        assertNull(decoded.difficulty)
        assertNull(decoded.lastReview)
        assertEquals(now, decoded.due)
    }

    @Test
    fun partiallyPopulatedLegacyCardIsResetBeforeScheduling() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val decoded = FsrsCardCodec.fromJson(
            """
            {
              "state": "LEARNING",
              "step": 1,
              "stability": null,
              "difficulty": 4.2,
              "due": ${now.toEpochMilli()},
              "lastReview": ${now.toEpochMilli()}
            }
            """.trimIndent()
        )

        assertEquals(State.LEARNING, decoded.state)
        assertNull(decoded.stability)
        assertNull(decoded.difficulty)
        assertNull(decoded.lastReview)
        assertEquals(0, decoded.step)

        val reviewed = scheduler.reviewCard(
            decoded,
            LearningRating.GOOD.toFsrs(),
            now
        ).card()
        assertTrue(reviewed.due.isAfter(now))
        assertTrue(reviewed.stability != null)
        assertTrue(reviewed.difficulty != null)
    }

    @Test
    fun invalidReviewedStateDoesNotReachFsrsWithNullStability() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val decoded = FsrsCardCodec.fromJson(
            "{\"state\":\"REVIEW\",\"stability\":null,\"difficulty\":null,\"lastReview\":${now.toEpochMilli()}}"
        )

        assertEquals(State.LEARNING, decoded.state)
        assertNull(decoded.stability)
        assertNull(decoded.difficulty)
        assertNull(decoded.lastReview)

        val reviewed = scheduler.reviewCard(
            decoded,
            LearningRating.AGAIN.toFsrs(),
            now
        ).card()
        assertTrue(reviewed.due.isAfter(now))
    }
}

package com.pagetime.app.data.review

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.CardAndReviewLog
import io.github.openspacedrepetition.Rating
import java.time.Instant

/**
 * How a card gets scheduled, from the caller's point of view.
 *
 * This exists so that the three places that schedule a card — a chapter
 * flashcard, a slip-box note in training, and a reading chunk — cannot drift
 * apart again. They used to each build their own `Scheduler`, and one of them
 * built a different one from the other two without anybody noticing; see
 * [FsrsScheduling] for what that cost. Here there is one implementation, and a
 * caller has no way to get a differently-configured one.
 *
 * [seedKey] is the card's own id. It is part of the signature rather than
 * something the scheduler guesses because it is what makes the interval shown
 * on a rating button identical to the interval that rating will actually
 * produce.
 *
 * Suspending because the policy is read from the reader's settings, and those
 * are in DataStore. Reading them per review is the point: a change in Settings
 * has to take effect on the next card, not on the next launch.
 */
interface CardScheduler {

    suspend fun review(
        seedKey: String,
        card: Card,
        rating: Rating,
        now: Instant,
    ): CardAndReviewLog

    companion object {
        /**
         * The stock policy, for callers that were constructed without a
         * settings-backed one.
         *
         * Only ever reached in tests and in previews, never in the running app:
         * [com.pagetime.app.data.AppContainer] always supplies the reader's own.
         */
        val DEFAULT: CardScheduler = object : CardScheduler {
            override suspend fun review(
                seedKey: String,
                card: Card,
                rating: Rating,
                now: Instant,
            ): CardAndReviewLog = FsrsScheduling.review(
                policy = SchedulingPolicy(),
                card = card,
                rating = rating,
                now = now,
                seed = FsrsScheduling.seed(seedKey, rating.value),
            )
        }
    }
}

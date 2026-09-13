package com.pagetime.app.data.review

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.CardAndReviewLog
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Duration
import java.time.Instant
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The one place in the app where a card is scheduled.
 *
 * THREE SCHEDULERS USED TO DISAGREE
 *
 * This used to be three separate `Scheduler.builder()` calls: one in
 * [com.pagetime.app.data.LumenRepository] and one in
 * [com.pagetime.app.data.PagemarkRepository], both passing
 * `desiredRetention(0.9).enableFuzzing(false)`, and one in [ChapterCardGrader]
 * passing nothing at all — which left fuzzing on, because that is the
 * scheduler library's default. So the same rating produced differently-fuzzed
 * intervals depending on which kind of card it was, and two comments in the
 * codebase confidently described the schedulers as identical. They were not.
 *
 * Worse than the inconsistency was what it did to the buttons. The interval
 * caption under each rating is computed by calling the scheduler, and grading
 * calls it again; with fuzzing on, those are two different draws from the same
 * `Random`, so the number on the button was not the number the reader got.
 *
 * The fix is [seed], which makes the draw a function of the card and the rating
 * rather than of how many times the scheduler has been asked. Preview and
 * grading then produce the identical interval by construction, so fuzzing can
 * stay on — which it should, because fuzzing is what stops a day's cards from
 * piling up on the same date — without the caption ever lying.
 */
object FsrsScheduling {

    /**
     * A seed that depends only on which card and which rating.
     *
     * `String.hashCode` is specified by the JDK, so this is stable across runs,
     * across installs, and across the app being killed mid-review — which it has
     * to be, or a caption computed before a rotation would disagree with a
     * grading after it.
     */
    fun seed(seedKey: String, ratingValue: Int): Long =
        seedKey.hashCode().toLong() * 1_000_003L + ratingValue

    /**
     * A scheduler configured the reader's way, for one card and one rating.
     *
     * Building one of these is a handful of field assignments — cheap enough to
     * do per call, which is what makes the seed trick possible.
     *
     * FSRS PARAMETERS ARE NOT PASSED, DELIBERATELY
     *
     * The twenty-one weights stay at the library's published FSRS-5 defaults.
     * There is no optimizer in this app — fitting them needs a training loop
     * over the reader's whole review history, which is Anki's "Optimize" button
     * and not something a phone can do between cards — so the only reachable
     * alternative to the defaults is a number the reader guessed, and the
     * library's own documentation for `parameters()` says not to.
     */
    fun scheduler(policy: SchedulingPolicy, seed: Long): Scheduler = Scheduler.builder()
        .desiredRetention(policy.desiredRetention)
        .learningSteps(policy.learningSteps.toTypedArray())
        .relearningSteps(policy.relearningSteps.toTypedArray())
        .maximumInterval(policy.maximumIntervalDays)
        .enableFuzzing(policy.fuzzingEnabled)
        .randomSeed(Random(seed))
        .build()

    /**
     * Reviews [card] with [rating] and returns the library's own result, with
     * the minimum interval applied.
     */
    fun review(
        policy: SchedulingPolicy,
        card: Card,
        rating: Rating,
        now: Instant,
        seed: Long,
    ): CardAndReviewLog {
        val result = scheduler(policy, seed).reviewCard(card, rating, now, null)

        // Anki's Minimum interval is applied AFTER the scheduler, and only to a
        // card that has finished learning — which is exactly where the library
        // leaves a card in the REVIEW state. A step is not clamped: the reader
        // said "come back in ten minutes" and a one-day floor would turn that
        // setting into a lie. Applied after fuzzing too, so the floor is a
        // floor.
        val updated = result.card()
        if (updated.state == State.REVIEW) {
            val floor = now.plus(Duration.ofDays(policy.minimumIntervalDays.toLong()))
            val due = updated.due
            if (due == null || due.isBefore(floor)) {
                updated.due = floor
            }
        }
        return result
    }

    /**
     * The interval FSRS would give a card with this [stability], in days.
     *
     * This is FSRS's own `next_interval` formula, and it is duplicated here for
     * one reason: the library computes it privately, so rescheduling an
     * existing card — re-deriving its due date after the reader changes their
     * desired retention — is otherwise impossible without pretending to review
     * the card again, which would compound its stability and corrupt it.
     *
     * Duplicated arithmetic is a liability, so it is pinned by
     * [FsrsSchedulingTest] against the library's own answer rather than trusted.
     * The parameters are read back off a scheduler built from [policy] rather
     * than hardcoded, so the two cannot drift when the library's defaults move.
     */
    fun intervalDaysFor(stability: Double, policy: SchedulingPolicy): Int {
        val parameters = scheduler(policy, SEEDLESS).parameters
        val decay = -parameters[DECAY_PARAMETER_INDEX]
        val factor = REFERENCE_RETENTION.pow(1.0 / decay) - 1.0
        val days = ((stability / factor) * (policy.desiredRetention.pow(1.0 / decay) - 1.0))
            .roundToInt()
        return days
            .coerceAtLeast(policy.minimumIntervalDays)
            .coerceAtMost(policy.maximumIntervalDays)
    }

    /**
     * The reference retention inside FSRS's decay constant.
     *
     * Not the reader's desired retention: `FACTOR` is a property of the curve
     * and is fixed at 0.9 in the reference implementation, while the reader's
     * value enters only through the numerator. Using their value for both would
     * silently make the formula a no-op.
     */
    private const val REFERENCE_RETENTION = 0.9

    /** `w[20]`, the decay parameter, in FSRS-5's parameter order. */
    private const val DECAY_PARAMETER_INDEX = 20

    /** Irrelevant when all this needs is the parameter array, but a seed is required. */
    private const val SEEDLESS = 0L
}

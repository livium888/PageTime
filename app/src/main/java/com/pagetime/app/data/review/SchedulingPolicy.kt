package com.pagetime.app.data.review

import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * How the reader has asked to be scheduled.
 *
 * THE SUBSET OF ANKI'S DECK OPTIONS THAT SURVIVES FSRS
 *
 * Anki's deck options page has a lot on it, and turning FSRS on hides a good
 * part of it again — Graduating interval, Easy interval, Starting ease, the
 * SM-2 knobs — because FSRS derives those from a card's own stability rather
 * than from a per-deck constant. What is left, and what is here, is: the two
 * step lists, the minimum and maximum interval, desired retention, and fuzzing.
 * Adding Graduating interval here would be worse than useless: it would look
 * like a control and be ignored.
 *
 * WHY STEPS EXIST AT ALL WHEN FSRS SETS THE INTERVALS
 *
 * A step is the one place the reader overrules the algorithm. Steps are what
 * happen before a card has earned a stability worth trusting: a minute, ten
 * minutes, an hour. Every step answered Good advances to the next, Again sends
 * the card back to the first, and the last one answered Good graduates the card
 * and hands it to FSRS for good.
 *
 * THIS IS WHAT MAKES CARDS COME BACK IN TEN MINUTES
 *
 * The scheduler library ships Anki's defaults — 1m then 10m for new cards, 10m
 * for lapsed ones — and the app had never overridden them, so a brand-new card
 * answered Good was reliably shown again ten minutes later. That is the whole
 * of that complaint, and [DEFAULT_LEARNING_STEPS] is a single ten-minute step
 * so that Good on a new card graduates straight to a day-scale interval while
 * Again and Hard still bring the card back while the reader is sitting there.
 */
data class SchedulingPolicy(
    /** Delays before graduation, in order. Empty means a card graduates at once. */
    val learningSteps: List<Duration> = DEFAULT_LEARNING_STEPS,
    /** The same, for a card that was failed. Empty means it graduates at once. */
    val relearningSteps: List<Duration> = DEFAULT_RELEARNING_STEPS,
    /** Floor in days for a card that has finished learning. Anki calls this Minimum interval. */
    val minimumIntervalDays: Int = DEFAULT_MINIMUM_INTERVAL_DAYS,
    /** Ceiling in days. Anki's default is a hundred years. */
    val maximumIntervalDays: Int = DEFAULT_MAXIMUM_INTERVAL_DAYS,
    /** The recall probability FSRS aims for. Anki calls this Desired retention. */
    val desiredRetention: Double = DEFAULT_DESIRED_RETENTION,
    /** Whether the scheduler nudges intervals by a few percent. See [FsrsScheduling]. */
    val fuzzingEnabled: Boolean = DEFAULT_FUZZING_ENABLED,
) {

    /** No same-day return is possible: both step lists are empty. */
    val graduatesImmediately: Boolean
        get() = learningSteps.isEmpty() && relearningSteps.isEmpty()

    companion object {
        /**
         * ONE ten-minute step, where the library (and Anki) default to two.
         *
         * The difference is entirely in what Good does. With `1m 10m`, Good on a
         * new card advances to the second step and the card is back in ten
         * minutes. With a single `10m`, Good on a new card is the *last* step,
         * so the card graduates and FSRS gives it days. Again and Hard still
         * return the card in minutes, which is what makes them different
         * answers rather than four ways to say the same thing.
         */
        val DEFAULT_LEARNING_STEPS: List<Duration> = listOf(Duration.ofMinutes(10))
        val DEFAULT_RELEARNING_STEPS: List<Duration> = listOf(Duration.ofMinutes(10))

        /**
         * Anki's default. Worth knowing: FSRS already refuses to schedule a
         * graduated card in under a day, so at 1 this changes nothing, and
         * raising it is the only way to make it do anything. That is not a
         * defect to hide — it is the honest state of the knob, and the screen
         * says so.
         */
        const val DEFAULT_MINIMUM_INTERVAL_DAYS = 1
        const val DEFAULT_MAXIMUM_INTERVAL_DAYS = 36_500
        const val DEFAULT_DESIRED_RETENTION = 0.9
        const val DEFAULT_FUZZING_ENABLED = true

        /** Below about 70% the reader is relearning everything; above 97% the workload explodes. */
        const val MIN_RETENTION = 0.70
        const val MAX_RETENTION = 0.97

        const val MIN_MINIMUM_INTERVAL_DAYS = 1
        const val MAX_MINIMUM_INTERVAL_DAYS = 365
        const val MIN_MAXIMUM_INTERVAL_DAYS = 1
        const val MAX_MAXIMUM_INTERVAL_DAYS = 36_500

        /** More steps than this in a day is not learning, it is a treadmill. */
        const val MAX_STEP_COUNT = 8

        val MIN_STEP: Duration = Duration.ofMinutes(1)
        val MAX_STEP: Duration = Duration.ofDays(365)
    }

    /** The policy with every value inside its bounds, and the two intervals consistent. */
    fun normalised(): SchedulingPolicy {
        val minimum = minimumIntervalDays
            .coerceIn(MIN_MINIMUM_INTERVAL_DAYS, MAX_MINIMUM_INTERVAL_DAYS)
        return copy(
            learningSteps = learningSteps.take(MAX_STEP_COUNT),
            relearningSteps = relearningSteps.take(MAX_STEP_COUNT),
            minimumIntervalDays = minimum,
            // The ceiling can never be below the floor, or a card would have no
            // legal interval and the two settings would quietly contradict.
            maximumIntervalDays = maximumIntervalDays
                .coerceIn(MIN_MAXIMUM_INTERVAL_DAYS, MAX_MAXIMUM_INTERVAL_DAYS)
                .coerceAtLeast(minimum),
            desiredRetention = desiredRetention.coerceIn(MIN_RETENTION, MAX_RETENTION),
        )
    }
}

/**
 * Anki's step syntax, which is what the reader's settings screen speaks.
 *
 * `10m`, `1h`, `2d`, space separated. An empty string is a real and meaningful
 * value — it means "no steps, graduate immediately" — and Anki's own manual
 * documents it, so blank is parsed as an empty list rather than treated as
 * input the reader has not finished typing.
 */
object Steps {

    private val TOKEN = Regex("^(\\d+)(m|h|d)$")

    /**
     * The steps in [text], or null if any token is not a step.
     *
     * Null rather than "the ones that parsed", because the alternative is a
     * reader typing `10m 1x` and being silently scheduled by `10m` alone with no
     * hint that half their answer was thrown away.
     */
    fun parse(text: String): List<Duration>? {
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        if (tokens.size > SchedulingPolicy.MAX_STEP_COUNT) return null

        val steps = mutableListOf<Duration>()
        for (token in tokens) {
            val match = TOKEN.matchEntire(token.lowercase()) ?: return null
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit = when (match.groupValues[2]) {
                "m" -> ChronoUnit.MINUTES
                "h" -> ChronoUnit.HOURS
                else -> ChronoUnit.DAYS
            }
            val step = runCatching { Duration.of(amount, unit) }.getOrNull() ?: return null
            if (step < SchedulingPolicy.MIN_STEP || step > SchedulingPolicy.MAX_STEP) return null
            steps += step
        }

        // Ascending, because a later step shorter than an earlier one schedules
        // the card sooner for doing better — which is not a preference anyone
        // holds, it is a typo.
        if (steps.zipWithNext().any { (earlier, later) -> later < earlier }) return null

        return steps
    }

    /** The stored form: `10m`, or `1m 10m 1d`, or empty for no steps. */
    fun format(steps: List<Duration>): String = steps.joinToString(" ") { formatOne(it) }

    private fun formatOne(step: Duration): String = when {
        step.seconds % 86_400L == 0L -> "${step.toDays()}d"
        step.seconds % 3_600L == 0L -> "${step.toHours()}h"
        else -> "${step.toMinutes()}m"
    }

    private val WHITESPACE = Regex("\\s+")
}

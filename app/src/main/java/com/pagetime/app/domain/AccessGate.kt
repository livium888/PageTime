package com.pagetime.app.domain

/**
 * Whether the blocked apps are open, under the "earn the day" rule.
 *
 * WHY THIS REPLACES THE RATIO
 *
 * The original design was a currency: reading seconds bought browsing seconds
 * at [Settings.ratio], and the balance drained one per second while a blocked
 * app was in front. It is a clean mechanism and it fails at the only thing it
 * was built for, because a currency is divisible. Read for a minute, spend a
 * minute, read another minute — the block becomes a toll rather than a
 * boundary, and the toll is cheap enough to pay over and over without ever
 * settling into reading.
 *
 * A gate is not divisible. Below the line nothing is purchasable at any price;
 * above it, nothing is metered. There is no rate to optimise and no reason to
 * bargain with it in one-minute increments, because a minute buys nothing.
 *
 * WHAT COUNTS
 *
 * Reading, and — up to [planningCapSeconds] — time spent talking to the
 * assistant about what to read. Planning is genuinely part of the work and
 * shutting it out would push the reader to fake reading instead; capping it is
 * what stops "talking about reading" from becoming the whole two hours.
 *
 * THE CAP IS APPLIED HERE, NOT WHEN THE TIME IS RECORDED
 *
 * Every planning second is written to the ledger; only the first
 * [planningCapSeconds] of them count toward the gate. Recording capped values
 * would bake today's setting into permanent history and make the audit screen
 * lie about how long was actually spent. The cost is that lowering the cap can
 * close a gate that was open — correct, if unwelcome: the rule changed.
 *
 * NOTHING HERE KNOWS THE TIME
 *
 * The window this counts over is the caller's business. Everything below is a
 * function of its arguments, which is what makes the awkward cases — cap
 * larger than the threshold, a threshold of zero, more planning than anyone
 * could do in a day — answerable by a test rather than by running the app for
 * a day and watching.
 */
data class GateState(
    /** False while the reader is still on the old ratio-and-balance model. */
    val enabled: Boolean,
    /** Creditable reading in the window. */
    val readingSeconds: Long,
    /** Assistant time in the window, BEFORE the cap. */
    val planningSeconds: Long,
    val thresholdSeconds: Long,
    val planningCapSeconds: Long,
) {

    /**
     * The cap actually in force, which is never the whole gate.
     *
     * A stored cap at or above [thresholdSeconds] would mean the apps could be
     * opened by talking and no reading at all — not a stricter or a looser
     * setting but a different feature, and one nobody asked for. The settings
     * screen keeps the slider well below this; the clamp is here because a
     * value that arrives some other way (an old preference, a hand-edited
     * store, a threshold lowered underneath a cap that was fine yesterday)
     * should not be able to turn the gate off.
     */
    val effectivePlanningCapSeconds: Long
        get() = planningCapSeconds.coerceIn(0L, thresholdSeconds.coerceAtLeast(0L))

    /** How much of [planningSeconds] the gate is willing to count. */
    val countedPlanningSeconds: Long
        get() = planningSeconds.coerceIn(0L, effectivePlanningCapSeconds)

    /** Reading plus counted planning: the number measured against the line. */
    val accruedSeconds: Long
        get() = readingSeconds.coerceAtLeast(0L) + countedPlanningSeconds

    /** Still to do before the apps open. Zero once they have. */
    val remainingSeconds: Long
        get() = (thresholdSeconds - accruedSeconds).coerceAtLeast(0L)

    /**
     * Planning time that would still count if it were spent now.
     *
     * Shown next to the cap so the reader can see the bucket emptying rather
     * than discovering it empty when the counter stops moving.
     */
    val planningRemainingSeconds: Long
        get() = (effectivePlanningCapSeconds - planningSeconds).coerceAtLeast(0L)

    /**
     * Whether blocked apps may be opened.
     *
     * Note what is NOT here: the balance. Under the gate a balance cannot open
     * anything, which is the whole point of the change, so the old figure is
     * left alone rather than spent down — it is a few minutes of credit, not a
     * savings account, and quietly draining it would be the currency logic
     * surviving one more release.
     */
    val open: Boolean
        get() = !enabled || accruedSeconds >= thresholdSeconds

    /** 0..1, for a bar. A threshold of zero is a gate that is always open. */
    val progress: Float
        get() = if (thresholdSeconds <= 0L) 1f
        else (accruedSeconds.toFloat() / thresholdSeconds).coerceIn(0f, 1f)

    companion object {

        /**
         * Two hours, which is the number the reader asked for.
         *
         * It is deliberately not tuneable down to a token amount from the
         * block screen — a gate you can lower while standing at it is a
         * button that says "open".
         */
        const val DEFAULT_THRESHOLD_SECONDS = 2L * 60 * 60

        /**
         * Twenty minutes of the two hours may be talking rather than reading.
         *
         * Enough to plan a week of reading without being enough to replace an
         * evening of it.
         */
        const val DEFAULT_PLANNING_CAP_SECONDS = 20L * 60

        /**
         * The shortest gate the settings screen will accept.
         *
         * Not zero. A gate that can be set to nothing is a gate with an
         * "open" button on it, and the reader asked for the opposite of that.
         * Fifteen minutes is enough to try the mechanism for one evening
         * without committing to two hours of it.
         */
        const val MIN_THRESHOLD_SECONDS = 15L * 60

        /** A working day of reading. Past this the setting is not serious. */
        const val MAX_THRESHOLD_SECONDS = 8L * 60 * 60

        /**
         * The largest share of a gate that may be planning rather than reading.
         *
         * Half. Talking about what to read is real work and shutting it out
         * would only push the reader into faking reading time instead; being
         * able to talk your way past MOST of the gate would make it a
         * conversation you have to sit through rather than a reading habit.
         */
        fun maxPlanningCapFor(thresholdSeconds: Long): Long =
            (thresholdSeconds.coerceAtLeast(0L)) / 2

        /** The gate before anything is known: closed only once switched on. */
        val Disabled = GateState(
            enabled = false,
            readingSeconds = 0,
            planningSeconds = 0,
            thresholdSeconds = DEFAULT_THRESHOLD_SECONDS,
            planningCapSeconds = DEFAULT_PLANNING_CAP_SECONDS,
        )
    }
}

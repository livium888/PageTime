package com.pagetime.app.domain

/**
 * Reading buys a session, and a session is the only way into a blocked app.
 *
 * WHY NOT A RATIO, AND WHY NOT A STANDING GATE EITHER
 *
 * The first design was a currency: reading seconds bought browsing seconds at
 * a ratio, drained a second at a time. It fails because a currency is
 * divisible — read a minute, spend a minute, read another minute. The block
 * becomes a toll rather than a boundary, and the toll is cheap enough to pay
 * over and over without ever settling into reading.
 *
 * The second design overcorrected into a standing gate: two hours of reading
 * and the apps simply opened until that reading aged out. That removes the
 * nibbling but replaces it with an all-day pass, which is a strange reward for
 * an afternoon of reading and gives the reader nothing to aim at once they
 * have crossed the line.
 *
 * This is the third and it keeps what each got right. There IS an exchange
 * rate — [DEFAULT_SESSION_COST_SECONDS] of reading for
 * [DEFAULT_SESSION_LENGTH_SECONDS] of apps, four to one — but the purchase is
 * INDIVISIBLE. You cannot buy a minute at any price. Indivisibility was always
 * the fix; the absence of trade never was.
 *
 * THE SESSION IS METERED, NOT A WALL CLOCK
 *
 * It was a wall clock for exactly one commit, and that was wrong. Thirty
 * minutes counting down in real time means putting the phone down to answer
 * the door costs you minutes you paid two hours of reading for — the same
 * theft the spend ticker already refuses to commit when the screen is off,
 * just wearing a nicer name.
 *
 * So session time only drains while a blocked app is actually in front, and
 * it does not expire. What is left is still there tomorrow.
 *
 * The worry that this rebuilds minute-nibbling does not survive contact with
 * the arithmetic. Nibbling was possible because the PURCHASE was divisible —
 * a minute of reading bought a minute of scrolling. Here the smallest thing
 * that can be bought is thirty minutes and it costs two hours. How that
 * thirty is spent afterwards was never the problem.
 *
 * CREDIT IS BANKED, BUT NOT INDEFINITELY
 *
 * Reading past the cost of one session keeps counting, up to [maxCreditFor],
 * which is two sessions' worth. A long Sunday is not wasted; it also cannot
 * fund a whole lost week. There is no rolling window and no nightly expiry —
 * credit is a counter that fills and empties, which is both simpler and easier
 * to explain than "your reading from Tuesday afternoon has just expired".
 *
 * TURNING IT OFF TAKES A DAY
 *
 * A boundary you can remove at the moment you want to cross it is not a
 * boundary. But an app with no exit gets uninstalled, and uninstalling is the
 * one hole nothing can plug — so the exit is real and merely slow:
 * [COOLING_OFF_MILLIS] after the switch is flipped. Turning it back ON is
 * instant, because more restriction never needs protecting from the reader.
 *
 * EVERYTHING HERE IS A FUNCTION OF ITS ARGUMENTS
 *
 * Including [nowMillis], which is passed in rather than read. The cooling-off
 * period is the one rule left that depends on the clock, and a rule that reads
 * the clock itself can only be tested by waiting.
 */
data class GateState(
    /** The stored switch. Not the same as [enabled] while winding down. */
    val switchedOn: Boolean,
    /** Reading banked toward the next session. */
    val creditSeconds: Long,
    /**
     * App time bought and not yet used, in seconds.
     *
     * A counter rather than a deadline, which is what makes it survive a
     * night on the bedside table.
     */
    val sessionSecondsRemaining: Long,
    /** When the cooling-off finishes and the gate really switches off; 0 = none. */
    val disableAtMillis: Long,
    val nowMillis: Long,
    val sessionCostSeconds: Long = DEFAULT_SESSION_COST_SECONDS,
    val sessionLengthSeconds: Long = DEFAULT_SESSION_LENGTH_SECONDS,
) {

    /**
     * Whether the gate is really in force.
     *
     * The switch and the answer differ for exactly one day, after the reader
     * asks to turn it off. Computing it here rather than flipping a stored
     * flag on a timer means nothing has to run for the gate to expire — the
     * next question anyone asks gets the right answer.
     */
    val enabled: Boolean
        get() = switchedOn && !(disableAtMillis > 0L && nowMillis >= disableAtMillis)

    /** The reader has asked to turn it off and the day has not yet passed. */
    val windingDown: Boolean
        get() = enabled && disableAtMillis > 0L

    /** How long until the gate switches itself off. Zero when not winding down. */
    val secondsUntilDisabled: Long
        get() = if (!windingDown) 0L else ((disableAtMillis - nowMillis) / 1000).coerceAtLeast(0L)

    /**
     * Credit, floored at zero.
     *
     * The stored counter is a long that has been added to and subtracted from
     * since the app was installed; a bug anywhere upstream that drove it
     * negative should cost the reader nothing and, more importantly, should
     * never make the next session look FURTHER away than a fresh install
     * would.
     */
    private val banked: Long
        get() = creditSeconds.coerceAtLeast(0L)

    val sessionActive: Boolean
        get() = enabled && sessionSecondsRemaining > 0L

    val sessionRemainingSeconds: Long
        get() = sessionSecondsRemaining.coerceAtLeast(0L)

    /** Whether blocked apps may be opened right now. */
    val open: Boolean
        get() = !enabled || sessionActive

    /**
     * Enough read to buy more app time.
     *
     * Buying again while time is still unspent is allowed — it is the
     * reader's two hours — but capped by [maxSessionSecondsFor] so app time
     * cannot be hoarded without limit.
     */
    val canStartSession: Boolean
        get() = enabled &&
            banked >= sessionCostSeconds &&
            sessionSecondsRemaining < maxSessionSecondsFor(sessionLengthSeconds)

    /** Still to read before the next session can be started. */
    val secondsToNextSession: Long
        get() = (sessionCostSeconds - banked).coerceAtLeast(0L)

    /** Whole sessions currently affordable. */
    val sessionsBanked: Int
        get() = if (sessionCostSeconds <= 0L) 0 else (banked / sessionCostSeconds).toInt()

    /** 0..1 toward the next session, for a bar. */
    val creditProgress: Float
        get() = when {
            sessionCostSeconds <= 0L -> 1f
            else -> {
                val towardNext = banked % sessionCostSeconds
                // A full session banked reads as full, not as back to zero.
                if (banked >= sessionCostSeconds && towardNext == 0L) 1f
                else (towardNext.toFloat() / sessionCostSeconds).coerceIn(0f, 1f)
            }
        }

    /**
     * Whether the rules may be made EASIER right now.
     *
     * The rule that makes the rest of this mean anything, and it has to cover
     * every way out, not just the obvious one. Taking an app off the blocked
     * list is one. Dragging the price of a session down to fifteen minutes is
     * another, and it is worse: it does not merely unblock one app, it
     * dissolves the whole gate, from the settings screen, while locked out.
     *
     * Loosening therefore costs what entry costs — a session, which is the
     * same two hours. The escape and the front door have the same price, so
     * there is nothing to be gained by reaching for the escape.
     *
     * TIGHTENING IS ALWAYS ALLOWED
     *
     * Blocking another app, raising the price, shortening the session: none of
     * those is an escape, and making someone earn the right to be stricter
     * with themselves would be perverse. The asymmetry is the whole design.
     *
     * The hard lock overrides this in the tightening direction; that is
     * checked by its own screen, because a hard lock is about a promise the
     * reader made and has nothing to do with what they have read.
     */
    val canLoosenTheRules: Boolean
        get() = !enabled || sessionActive

    /** Removing an app is one way of loosening. */
    val canRemoveBlockedApps: Boolean
        get() = canLoosenTheRules

    companion object {

        /** Two hours of reading. */
        const val DEFAULT_SESSION_COST_SECONDS = 2L * 60 * 60

        /** Buys thirty minutes. */
        const val DEFAULT_SESSION_LENGTH_SECONDS = 30L * 60

        /**
         * The ceiling on unspent app time: two sessions' worth.
         *
         * Time does not expire, so without a ceiling a reader could stockpile
         * an afternoon of app time across a month of reading and then spend a
         * week not reading at all — the currency returning in a larger
         * denomination, which is the failure this design exists to avoid.
         */
        fun maxSessionSecondsFor(sessionLengthSeconds: Long): Long =
            (sessionLengthSeconds.coerceAtLeast(0L)) * 2

        /** How long turning the gate off takes to take effect. */
        const val COOLING_OFF_MILLIS = 24L * 60 * 60 * 1000

        /**
         * The shortest session cost the settings screen will accept.
         *
         * Not zero, and not five minutes. A cost you can lower to nothing
         * while standing at the block screen is an "open" button with extra
         * steps. Fifteen minutes is enough to try the mechanism for one
         * evening without committing to two hours of it.
         */
        const val MIN_SESSION_COST_SECONDS = 15L * 60

        /** A working day of reading. Past this the setting is not serious. */
        const val MAX_SESSION_COST_SECONDS = 8L * 60 * 60

        const val MIN_SESSION_LENGTH_SECONDS = 5L * 60
        const val MAX_SESSION_LENGTH_SECONDS = 2L * 60 * 60

        /**
         * The ceiling on banked credit: two sessions' worth.
         *
         * Reading past this still counts as reading — it is recorded, and the
         * book still advances — it simply stops buying anything. The cap is
         * what stops a heavy weekend from funding a week of not reading,
         * which would turn the whole mechanism back into a currency with a
         * larger denomination.
         */
        fun maxCreditFor(sessionCostSeconds: Long): Long =
            (sessionCostSeconds.coerceAtLeast(0L)) * 2

        /**
         * Whether a proposed session price is a loosening.
         *
         * Cheaper is easier. Written as a named function rather than a `<`
         * at the call site because the direction is not self-evident for a
         * cost — the number going DOWN is the gate getting weaker — and the
         * one place it is written down should say so.
         */
        fun loosensCost(currentSeconds: Long, proposedSeconds: Long): Boolean =
            proposedSeconds < currentSeconds

        /** Longer sessions are easier, so the direction is the other way round. */
        fun loosensLength(currentSeconds: Long, proposedSeconds: Long): Boolean =
            proposedSeconds > currentSeconds

        /**
         * What the price slider may commit when a touch asks for [proposedSeconds].
         *
         * The slider used to clip its own travel instead of coming here: the
         * range began at the current price, so the thumb sat at the far left
         * end and the track still spanned the whole distance to the ceiling.
         * Compose maps a touch across the whole track into the range, so a
         * finger anywhere past the left edge read as "move a long way toward
         * the maximum" — and because each write moved the range's start up to
         * the new price, the next touch jumped again. Touching the slider made
         * the reading target climb. That was the reported bug, and it was the
         * mapping, not the rule.
         *
         * The slider keeps the FULL range now, so the thumb sits where the
         * setting actually is and a touch near it means what it looks like.
         * The direction is clamped here, which is the only thing that ever
         * needed saying: a proposal in the strict direction is taken as given,
         * and one in the easy direction only when the reader has app time in
         * hand. Everything outside the range is pinned to it, exactly as the
         * repository would pin it on the way to storage.
         */
        fun committedCostSeconds(
            currentSeconds: Long,
            proposedSeconds: Long,
            canLoosenTheRules: Boolean,
        ): Long = commit(
            currentSeconds = currentSeconds.coerceIn(MIN_SESSION_COST_SECONDS, MAX_SESSION_COST_SECONDS),
            proposedSeconds = proposedSeconds.coerceIn(MIN_SESSION_COST_SECONDS, MAX_SESSION_COST_SECONDS),
            loosens = { current, proposed -> loosensCost(current, proposed) },
            canLoosenTheRules = canLoosenTheRules,
        )

        /** The same rule for session length, where SHORTER is the strict direction. */
        fun committedLengthSeconds(
            currentSeconds: Long,
            proposedSeconds: Long,
            canLoosenTheRules: Boolean,
        ): Long = commit(
            currentSeconds = currentSeconds.coerceIn(MIN_SESSION_LENGTH_SECONDS, MAX_SESSION_LENGTH_SECONDS),
            proposedSeconds = proposedSeconds.coerceIn(MIN_SESSION_LENGTH_SECONDS, MAX_SESSION_LENGTH_SECONDS),
            loosens = { current, proposed -> loosensLength(current, proposed) },
            canLoosenTheRules = canLoosenTheRules,
        )

        /**
         * A proposal that would make the gate weaker is refused unless the
         * reader has app time in hand; anything else stands. Returning the
         * current value rather than null is what lets the slider leave the
         * thumb alone instead of writing a number the repository would throw
         * away a moment later.
         */
        private fun commit(
            currentSeconds: Long,
            proposedSeconds: Long,
            loosens: (Long, Long) -> Boolean,
            canLoosenTheRules: Boolean,
        ): Long =
            if (loosens(currentSeconds, proposedSeconds) && !canLoosenTheRules) currentSeconds
            else proposedSeconds

        /** Before anything is known: off, so nothing is blocked on a guess. */
        val Unknown = GateState(
            switchedOn = false,
            creditSeconds = 0,
            sessionSecondsRemaining = 0,
            disableAtMillis = 0,
            nowMillis = 0,
        )
    }
}

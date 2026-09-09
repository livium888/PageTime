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
 * THE SESSION RUNS ON A WALL CLOCK
 *
 * Thirty minutes from the moment it is opened, running whether the phone is
 * used or not. Metering it only while a blocked app is in front would rebuild
 * minute-nibbling inside the window, and would make putting the phone down
 * feel like saving — the opposite of the lesson. A session is a block of time
 * that is spent, not a balance that is drawn down.
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
 * Including [nowMillis], which is passed in rather than read. Session expiry,
 * the cooling-off period and the countdown are all time-dependent, and a rule
 * that reads the clock itself can only be tested by waiting.
 */
data class GateState(
    /** The stored switch. Not the same as [enabled] while winding down. */
    val switchedOn: Boolean,
    /** Reading banked toward the next session. */
    val creditSeconds: Long,
    /** When the current session ends (epoch millis); 0 when none is running. */
    val sessionEndsAtMillis: Long,
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
        get() = enabled && nowMillis < sessionEndsAtMillis

    val sessionRemainingSeconds: Long
        get() = if (!sessionActive) 0L else ((sessionEndsAtMillis - nowMillis) / 1000).coerceAtLeast(0L)

    /** Whether blocked apps may be opened right now. */
    val open: Boolean
        get() = !enabled || sessionActive

    /** Enough read to open the door, and no session already running. */
    val canStartSession: Boolean
        get() = enabled && !sessionActive && banked >= sessionCostSeconds

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
     * Whether an app may be taken OFF the blocked list.
     *
     * The rule that makes the rest of this mean anything. Without it the gate
     * is decorative: two hours of reading, or Settings, uncheck, done. Removal
     * waits for a session — which costs the same two hours, so the escape and
     * the front door have the same price.
     *
     * ADDING an app is never restricted. More blocking is not an escape, and
     * making someone earn the right to block something would be perverse.
     *
     * The hard lock still overrides this; that is checked by its own screen,
     * because a hard lock is about a promise the reader made and has nothing
     * to do with what they have read.
     */
    val canRemoveBlockedApps: Boolean
        get() = !enabled || sessionActive

    companion object {

        /** Two hours of reading. */
        const val DEFAULT_SESSION_COST_SECONDS = 2L * 60 * 60

        /** Buys thirty minutes. */
        const val DEFAULT_SESSION_LENGTH_SECONDS = 30L * 60

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

        /** Before anything is known: off, so nothing is blocked on a guess. */
        val Unknown = GateState(
            switchedOn = false,
            creditSeconds = 0,
            sessionEndsAtMillis = 0,
            disableAtMillis = 0,
            nowMillis = 0,
        )
    }
}

package com.pagetime.app.domain

/**
 * The daily reading lock: read today's target and the phone opens until the
 * next reset; until then, only allowed apps do.
 *
 * WHY THIS REPLACED THE SESSION GATE
 *
 * The session gate (reading bought thirty indivisible minutes of blocked apps)
 * did its job too well: the reader stopped wanting the blocked apps, and once
 * nothing was wanted, nothing pulled them toward reading either. A reward only
 * works while it is still preferred. So the question this answers changed from
 * "has enough reading been banked to buy app time?" to "has today's reading
 * been done?" — see docs/decisions/0001-daily-reading-lock.md.
 *
 * The session design rejected exactly this shape once, as a "standing gate":
 * read, and the apps open all day. That objection was about rationing app
 * time, which was the old goal. Under the new one, the rest of the day opening
 * up is the intended reward; the daily target is the product, not the price.
 *
 * A DAY STARTS AT 04:00, NOT MIDNIGHT
 *
 * [readTodaySeconds] is already scoped to the current lock day by the caller
 * (see [LockDay]). A midnight reset would make reading in bed at 00:30 count
 * for a day that has barely started, and lock a phone at the moment someone is
 * going to sleep with it.
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
 * Including [nowMillis], which is passed in rather than read, so every rule —
 * the cooling-off included — can be tested without waiting.
 */
data class GateState(
    /** The stored switch. Not the same as [enabled] while winding down. */
    val switchedOn: Boolean,
    /**
     * Reading done in the current lock day, in seconds. Zero on a new day;
     * the caller resets it via [LockDay.readToday], so nothing here needs to
     * know what time the day starts.
     */
    val readTodaySeconds: Long,
    /** When the cooling-off finishes and the lock really switches off; 0 = none. */
    val disableAtMillis: Long,
    val nowMillis: Long,
    val dailyTargetSeconds: Long = DEFAULT_DAILY_TARGET_SECONDS,
) {

    /**
     * Whether the lock is really in force.
     *
     * Computed rather than stored, so a finished wind-down needs nothing to run
     * for the lock to expire — the next question anyone asks gets the right
     * answer.
     */
    val enabled: Boolean
        get() = switchedOn && !(disableAtMillis > 0L && nowMillis >= disableAtMillis)

    /** The reader has asked to turn it off and the day has not yet passed. */
    val windingDown: Boolean
        get() = enabled && disableAtMillis > 0L

    /** How long until the lock switches itself off. Zero when not winding down. */
    val secondsUntilDisabled: Long
        get() = if (!windingDown) 0L else ((disableAtMillis - nowMillis) / 1000).coerceAtLeast(0L)

    /**
     * The target, never below [MIN_DAILY_TARGET_SECONDS]. A stored zero (or a
     * corrupt negative) must not make the lock open itself for free.
     */
    val target: Long
        get() = dailyTargetSeconds.coerceAtLeast(MIN_DAILY_TARGET_SECONDS)

    /** Reading done today, floored at zero for the same reason. */
    val readToday: Long
        get() = readTodaySeconds.coerceAtLeast(0L)

    /** Today's reading is done. Independent of the switch, so the screen can say so either way. */
    val targetMet: Boolean
        get() = readToday >= target

    /** Whether blocked apps may be opened right now. */
    val open: Boolean
        get() = !enabled || targetMet

    /**
     * Whether today's reading also opens blocked sites.
     *
     * Not [open]: that is also true when the lock is simply switched off, at
     * which point an app falls back to the old browse balance — and a site
     * rule set up with the lock off must stay a rule, or blocking a site would
     * only ever work for someone who had also opted into the lock.
     */
    val coversSites: Boolean
        get() = enabled && targetMet

    /** Still to read today before the phone opens. */
    val secondsToUnlock: Long
        get() = (target - readToday).coerceAtLeast(0L)

    /** 0..1 toward today's target, for a bar. */
    val progress: Float
        get() = (readToday.toFloat() / target).coerceIn(0f, 1f)

    /**
     * Whether the rules may be made easier right now.
     *
     * The strict direction is always free — a higher target, fewer allowed
     * apps, any time. The easy direction is open only once today's reading is
     * done (or while the lock is off): the moment someone most wants to loosen
     * a rule is the moment it is stopping them, and that is exactly when it
     * must hold.
     */
    val canLoosenTheRules: Boolean
        get() = !enabled || targetMet

    /** Removing an app from a blocklist is one way of loosening. */
    val canRemoveBlockedApps: Boolean
        get() = canLoosenTheRules

    /**
     * Adding to an allowlist — sites or apps — is the other way. Outside its
     * one-time setup window (checked by the caller alongside this), adding
     * costs today's reading.
     */
    val canAddAllowedSite: Boolean
        get() = canLoosenTheRules

    val canAddAllowedApp: Boolean
        get() = canLoosenTheRules

    /**
     * Leaving an allowlist for a blocklist reopens everything the allowlist did
     * not name. The other direction only narrows and stays free.
     */
    val canSwitchToBlocklist: Boolean
        get() = canLoosenTheRules

    companion object {

        /** Twenty minutes: long enough to be reading, short enough to do every day. */
        const val DEFAULT_DAILY_TARGET_SECONDS = 20L * 60

        /**
         * Not zero: a target of nothing is an open door with extra steps. Five
         * minutes is enough to try the lock for one day without committing to
         * more.
         */
        const val MIN_DAILY_TARGET_SECONDS = 5L * 60

        /** Three hours. Past this the setting locks the phone for most of a day. */
        const val MAX_DAILY_TARGET_SECONDS = 3L * 60 * 60

        /** How long turning the lock off takes to take effect. */
        const val COOLING_OFF_MILLIS = 24L * 60 * 60 * 1000

        /** A lower target is the easy direction. */
        fun loosensTarget(currentSeconds: Long, proposedSeconds: Long): Boolean =
            proposedSeconds < currentSeconds

        /**
         * How far the target control may travel, in seconds.
         *
         * A range rather than a veto: a slider that springs back reads as
         * broken, while clipping its travel says the same thing honestly.
         *
         * The bug the old session-price fence shipped with must not return: the
         * screen wrote on every frame of the drag, so the lower bound (the
         * stored value) climbed under the finger and one touch ratcheted the
         * value to its ceiling. The screen holds a draft and commits once on
         * release, freezing these bounds for the drag. This function is pure
         * and knows none of that, which is why the rule can be tested.
         */
        fun targetBounds(currentSeconds: Long, canLoosen: Boolean): LongRange {
            // Clamped first, or a stored value outside the range would produce
            // an inverted range and a control with negative travel.
            val current = currentSeconds.coerceIn(MIN_DAILY_TARGET_SECONDS, MAX_DAILY_TARGET_SECONDS)
            return if (canLoosen) {
                MIN_DAILY_TARGET_SECONDS..MAX_DAILY_TARGET_SECONDS
            } else {
                current..MAX_DAILY_TARGET_SECONDS
            }
        }

        /** Whether a fenced control has any travel left at all. */
        fun hasTravel(bounds: LongRange): Boolean = bounds.last > bounds.first

        /** Before anything is known: off, so nothing is blocked on a guess. */
        val Unknown = GateState(
            switchedOn = false,
            readTodaySeconds = 0,
            disableAtMillis = 0,
            nowMillis = 0,
        )
    }
}

package com.pagetime.app.data.usage

/**
 * A single parsed UsageEvents sample. Kept as plain data so the interval math in
 * [ForegroundParser] is a pure function run entirely on the JVM in unit tests —
 * no Android dependency.
 */
data class UsageEventSample(
    val packageName: String?,
    val type: Int,
    val time: Long
)

/**
 * What was already true when a scan window opened: which packages were holding
 * the foreground, and whether the screen was interactive.
 *
 * This is the whole reason [ForegroundParser.scan] is correct across
 * consecutive windows. Android emits a foreground event ONCE, when an app is
 * opened — so a reader who opens Kindle and reads for an hour produces exactly
 * one event, in the first window, and none at all in the fifty-nine that
 * follow. A scan that rebuilt its state from each window's events alone would
 * see those windows as empty and measure nothing, which is precisely the shape
 * of "I read for ten minutes and it logged one".
 *
 * Carrying the closing state of one window into the next is what makes a long
 * sitting measurable at all. It is persisted rather than held in memory
 * because the process this runs in is routinely killed mid-sitting — which is
 * the same reason [UsageReconciler] exists.
 */
data class ForegroundState(
    /**
     * Packages foreground at the window's start. Deliberately NOT filtered to
     * the tracked set: which apps are tracked can change between sweeps, and an
     * app trusted while it is already open must still be measurable without
     * waiting for the reader to close and reopen it.
     */
    val openPackages: Set<String> = emptySet(),
    /**
     * Whether the screen was interactive. Defaults to true for the same reason
     * [ForegroundParser.scan] does: a window is presumed awake until an event
     * says otherwise, and we never retroactively decide an earlier minute was
     * asleep.
     */
    val interactive: Boolean = true,
) {
    /**
     * Flat string form for DataStore: "<1|0>|pkg,pkg". Android package names
     * cannot contain '|' or ',', so no escaping is needed.
     */
    fun encode(): String =
        (if (interactive) "1" else "0") + "|" + openPackages.joinToString(",")

    companion object {
        /**
         * Anything unparseable reads as the default state — screen awake,
         * nothing open. That is the conservative direction for both callers:
         * nothing to credit for a trusted app, and nothing to charge for a
         * blocked one, until the next real event says otherwise.
         */
        fun decode(raw: String?): ForegroundState {
            if (raw.isNullOrEmpty()) return ForegroundState()
            val separator = raw.indexOf('|')
            if (separator < 0) return ForegroundState()
            return ForegroundState(
                openPackages = raw.substring(separator + 1)
                    .split(',')
                    .filter { it.isNotBlank() }
                    .toSet(),
                interactive = raw[0] == '1',
            )
        }
    }
}

/**
 * One window's measurement: what was accrued, and what to carry forward.
 */
data class ForegroundScan(
    val millisByPackage: Map<String, Long>,
    val endState: ForegroundState,
)

/**
 * Turns a chronological stream of usage events into per-package foreground
 * seconds, counting only time when the screen was actually interactive.
 *
 * This is the anti-cheating audit layer: the accessibility service can only
 * charge while it is alive, so time spent in a blocked app while PageTime was
 * force-stopped or the service was dead was previously FREE. Android's
 * UsageStatsManager keeps recording regardless, and this parser reconstructs
 * the real foreground windows that the reconciler then charges retroactively
 * (deducting whatever the live ticker already banked, window by window).
 *
 * Screen-off (or keyguard shown) gaps inside a foreground interval are not
 * counted — nobody is using the app with the screen off, so charging them would
 * be theft. This mirrors the live ticker's PowerManager.isInteractive gate.
 *
 * Event types (android.app.usage.UsageEvents.Event):
 *  1 = foreground/resumed     2 = background/paused
 * 15 = screen interactive    16 = screen non-interactive
 * 17 = keyguard shown        18 = keyguard hidden
 */
class ForegroundParser {

    companion object {
        const val EVENT_FOREGROUND = 1
        const val EVENT_BACKGROUND = 2
        const val EVENT_SCREEN_INTERACTIVE = 15
        const val EVENT_SCREEN_NON_INTERACTIVE = 16
        const val EVENT_KEYGUARD_SHOWN = 17
        const val EVENT_KEYGUARD_HIDDEN = 18
    }

    /**
     * Measures [from]..[to], resuming from [startState] — what the previous
     * window closed with.
     *
     * Returns both the per-package interactive-foreground milliseconds
     * (restricted to [trackedPackages]) and the state to carry into the next
     * window. Callers MUST persist [ForegroundScan.endState] and pass it back,
     * or every window without a foreground event in it measures zero; see
     * [ForegroundState] for why that is the common case rather than an edge one.
     *
     * Package-agnostic on purpose: [UsageReconciler] tracks blocked apps to
     * charge them, and [ExternalReadingTracker] tracks trusted reader apps to
     * credit them — both are just "how long was this foreground", asked of a
     * different set.
     */
    fun scan(
        events: List<UsageEventSample>,
        trackedPackages: Set<String>,
        from: Long,
        to: Long,
        startState: ForegroundState = ForegroundState(),
    ): ForegroundScan {
        val sorted = events.sortedBy { it.time }

        // In-window screen/keyguard events toggle this from their own timestamp
        // onward. We never guess "off" from an in-window event retroactively —
        // a screen that turned off at minute 2 was on at minute 1.
        var interactive = startState.interactive

        // pkg -> wall time up to which this package's foreground has already
        // been charged. The interval stays open across screen-off gaps (re-opened
        // when the screen comes back) but no time accrues while the screen is off.
        // Seeded from the carried-in state so a sitting that began in an earlier
        // window keeps accruing through this one.
        val lastChargedUpTo = mutableMapOf<String, Long>()
        for (pkg in startState.openPackages) lastChargedUpTo[pkg] = from
        val totals = mutableMapOf<String, Long>()

        fun chargeTo(now: Long) {
            val t = now.coerceIn(from, to)
            if (interactive) {
                for ((pkg, lastCharge) in lastChargedUpTo) {
                    if (t > lastCharge) {
                        val s = lastCharge.coerceAtLeast(from)
                        totals[pkg] = (totals[pkg] ?: 0L) + (t - s)
                    }
                }
            }
            for (pkg in lastChargedUpTo.keys) lastChargedUpTo[pkg] = t
        }

        for (e in sorted) {
            if (e.time < from) {
                // Events theoretically before the window: establish state only.
                when (e.type) {
                    EVENT_FOREGROUND -> {
                        if (e.packageName != null) lastChargedUpTo[e.packageName] = from
                    }
                    EVENT_BACKGROUND -> lastChargedUpTo.remove(e.packageName)
                    EVENT_SCREEN_INTERACTIVE, EVENT_KEYGUARD_HIDDEN -> interactive = true
                    EVENT_SCREEN_NON_INTERACTIVE, EVENT_KEYGUARD_SHOWN -> interactive = false
                }
                continue
            }
            if (e.time >= to) break
            when (e.type) {
                EVENT_FOREGROUND -> {
                    chargeTo(e.time)
                    if (e.packageName != null) {
                        lastChargedUpTo[e.packageName] = e.time.coerceAtLeast(from)
                    }
                }
                EVENT_BACKGROUND -> {
                    chargeTo(e.time)
                    lastChargedUpTo.remove(e.packageName)
                }
                EVENT_SCREEN_INTERACTIVE, EVENT_KEYGUARD_HIDDEN -> {
                    chargeTo(e.time)
                    interactive = true
                }
                EVENT_SCREEN_NON_INTERACTIVE, EVENT_KEYGUARD_SHOWN -> {
                    chargeTo(e.time)
                    interactive = false
                }
            }
        }
        chargeTo(to)

        return ForegroundScan(
            millisByPackage = totals.filterKeys { it in trackedPackages },
            endState = ForegroundState(
                openPackages = lastChargedUpTo.keys.toSet(),
                interactive = interactive,
            ),
        )
    }
}
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
     * Returns, for each package in [blockedPackages], the number of milliseconds
     * it was foreground with the screen interactive, clipped to [from]..[to].
     * Events are assumed to be in [from]..[to] (queryEvents semantics); the
     * clipping is defensive.
     */
    fun screenOnForegroundMillis(
        events: List<UsageEventSample>,
        blockedPackages: Set<String>,
        from: Long,
        to: Long
    ): Map<String, Long> {
        if (blockedPackages.isEmpty()) return emptyMap()
        val sorted = events.sortedBy { it.time }

        // Start with the screen state we can observe inside the window: if the
        // first screen/keyguard event says "off", assume the window started off.
        var interactive = screenOnAtStart(sorted)

        // pkg -> wall time up to which this package's foreground has already
        // been charged. The interval stays open across screen-off gaps (re-opened
        // when the screen comes back) but no time accrues while the screen is off.
        val lastChargedUpTo = mutableMapOf<String, Long>()
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

        return totals.filterKeys { it in blockedPackages }
    }

    private fun screenOnAtStart(sorted: List<UsageEventSample>): Boolean {
        val firstScreenEvent = sorted.firstOrNull {
            it.type == EVENT_SCREEN_INTERACTIVE || it.type == EVENT_SCREEN_NON_INTERACTIVE ||
                it.type == EVENT_KEYGUARD_SHOWN || it.type == EVENT_KEYGUARD_HIDDEN
        } ?: return true
        return firstScreenEvent.type == EVENT_SCREEN_INTERACTIVE ||
            firstScreenEvent.type == EVENT_KEYGUARD_HIDDEN
    }
}
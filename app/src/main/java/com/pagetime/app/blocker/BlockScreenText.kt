package com.pagetime.app.blocker

import com.pagetime.app.domain.GateState

/**
 * What the block screen says.
 *
 * A DISTANCE, NOT A DEBT
 *
 * The old screen said "Time is up!", which is the correct sentence for a
 * currency: something was spent and now there is none. Under the daily
 * reading lock nothing was spent — today's reading simply is not done yet. So
 * the screen reports a distance ("12m of 20m read today") and how far is left,
 * because a distance is a thing the reader can close.
 *
 * There is no button to buy anything any more. The way through is the "Read
 * now" button that was always there, and the reward for using it is the rest
 * of the phone until 04:00.
 *
 * Kept out of the overlay class so the wording and the arithmetic can be
 * tested without a WindowManager.
 */
object BlockScreenText {

    /** Whether to draw the progress bar, and how full. Null on the browse balance. */
    fun progress(gate: GateState): Float? = if (gate.enabled) gate.progress else null

    fun title(gate: GateState): String = when {
        !gate.enabled -> "Time is up!"
        // Read today is the target minus what is left, so the two halves of
        // this sentence and the subtitle can never disagree.
        else -> "${span(gate.target - gate.secondsToUnlock)} of ${span(gate.target)} read today"
    }

    fun subtitle(gate: GateState): String = when {
        !gate.enabled -> "Read a few minutes to earn time in this app."
        gate.targetMet -> "Today's reading is done. Your phone is open until 4am."
        else -> "Read ${span(gate.secondsToUnlock)} more to unlock your phone until 4am."
    }

    /**
     * The block screen for a site rather than an app.
     *
     * A DIFFERENT REASON, SO A DIFFERENT SENTENCE AGAIN
     *
     * Both existing sentences are wrong here, though for a narrower reason
     * than they used to be. "Time is up!" still describes a debt that does
     * not exist. The gate's distance ("1h 12m of 2h") is wrong for a
     * different one: reading DOES eventually open this site now, the same
     * session that opens a blocked app — but this screen only ever appears
     * while that session is not the answer, either because none is running
     * or because the reader has not read enough to start one, and repeating
     * the app screen's distance here would explain the wrong rule (site
     * versus app) for the number it shows. So the screen says the address,
     * says that it is off limits, and offers the two things that are honest
     * at this moment: go back, or read instead — which is no longer merely
     * something to do while shut out, but the way to earn the session that
     * opens this exact page.
     *
     * The address is the title rather than a decoration. It is the one piece of
     * information that makes the screen explicable at a glance — a reader who
     * sees `bbc.co.uk` knows instantly which rule fired and can go and change
     * it — and naming the section when the rule names one shows that a rule
     * narrower than the whole site is being honoured as written.
     */
    fun siteTitle(rule: SiteRules.Rule): String = rule.id

    fun siteSubtitle(rule: SiteRules.Rule, mode: SiteMode): String = when (mode) {
        SiteMode.BLOCKLIST -> if (rule.pathPrefix == null) {
            "You put this site off limits. Go back, or read for a while."
        } else {
            "You put this part of the site off limits. Go back, or read for a while."
        }
        // No rule to name here — nothing was typed to explain this block, only
        // the absence of a rule that would have let it through. Saying so is
        // the honest version of "you put this off limits": the reader chose
        // the allowlist itself, just not this address.
        SiteMode.ALLOWLIST -> "This isn't on your allowed list. Go back, or read for a while."
    }

    /** The secondary action on a site block: leave the page. */
    fun siteBackLabel(): String = "Go back"

    /**
     * What the emergency button offers, or why it cannot.
     *
     * Names the app rather than saying "unlock", because naming it is the
     * honest description of what happens — one app opens and the rest stay
     * shut — and because a reader who reaches for this in a hurry should be
     * able to see at a glance that it is not a general bypass.
     */
    fun emergencyLabel(appLabel: String?): String =
        if (appLabel.isNullOrBlank()) "Open this app for 5 minutes"
        else "Open $appLabel for 5 minutes"

    /** The line under it: how many are left, or when the next one returns. */
    fun emergencyNote(usesLeft: Int, nextAvailableInSeconds: Long?): String = when {
        usesLeft > 1 -> "$usesLeft left this week. Only this app opens."
        usesLeft == 1 -> "1 left this week. Only this app opens."
        nextAvailableInSeconds != null ->
            "None left. The next one is back in ${span(nextAvailableInSeconds)}."
        else -> "None left this week."
    }

    /**
     * A duration a person would say out loud.
     *
     * Never seconds: the smallest thing this screen reports is a minute of
     * reading, and "1h 11m 58s" invites watching a number rather than reading.
     * Anything under a minute still has to say something, and "under a minute"
     * is the honest version of a zero that is not zero.
     */
    fun span(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val totalMinutes = seconds / 60
        if (totalMinutes <= 0) return "under a minute"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours <= 0 -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }
}

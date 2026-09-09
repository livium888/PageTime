package com.pagetime.app.blocker

import com.pagetime.app.domain.GateState

/**
 * What the block screen says.
 *
 * A DIFFERENT SENTENCE, NOT A DIFFERENT TONE
 *
 * The old screen said "Time is up!", which is the correct sentence for a
 * currency: something was spent, and now there is none. It is the wrong
 * sentence under the session gate, where nothing was spent and nothing ran
 * out — the reading for the next session simply is not done yet. So the screen
 * reports a DISTANCE ("1h 12m of 2h") rather than a debt, because a distance
 * is a thing the reader can close and a debt is a thing that happened to them.
 *
 * THE ONE MOMENT THIS SCREEN CAN SAY YES
 *
 * When the reading is already done, the block screen stops being a wall and
 * becomes the door: it offers the session rather than announcing a refusal.
 * That is deliberately the only place the offer appears besides Settings,
 * because it is the place the reader actually is when they want it.
 *
 * Kept out of the overlay class so the wording and the arithmetic can be
 * tested without a WindowManager, which is the only reason anything about the
 * block screen has ever been checkable.
 */
object BlockScreenText {

    /** Whether to draw the credit bar, and how full. Null on the browse balance. */
    fun progress(gate: GateState): Float? = if (gate.enabled) gate.creditProgress else null

    /** Whether the screen should offer to open a session. */
    fun showsStartButton(gate: GateState): Boolean = gate.canStartSession

    fun title(gate: GateState): String = when {
        !gate.enabled -> "Time is up!"
        gate.canStartSession -> "You've read enough"
        // Distance covered is the cost minus the distance left, so the two
        // halves of the sentence can never disagree with each other.
        else -> "${span(gate.sessionCostSeconds - gate.secondsToNextSession)} of " +
            span(gate.sessionCostSeconds)
    }

    fun subtitle(gate: GateState): String = when {
        !gate.enabled -> "Read a few minutes to earn time in this app."
        gate.canStartSession ->
            "Start your ${span(gate.sessionLengthSeconds)}. It only counts down " +
                "while you are using these apps."
        else ->
            "${span(gate.secondsToNextSession)} of reading before your next " +
                "${span(gate.sessionLengthSeconds)}."
    }

    /** The button that opens a session, when there is one to open. */
    fun startButtonLabel(gate: GateState): String = "Start ${span(gate.sessionLengthSeconds)}"

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
        usesLeft > 1 -> "$usesLeft left today. Only this app opens."
        usesLeft == 1 -> "1 left today. Only this app opens."
        nextAvailableInSeconds != null ->
            "None left. The next one is back in ${span(nextAvailableInSeconds)}."
        else -> "None left today."
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

    /**
     * App time left, which unlike [span] shows seconds.
     *
     * The opposite decision to the one above, for the opposite reason: a
     * session that says "1m" for a minute and then ends feels like it was
     * taken away. While something is being spent, the seconds are the
     * information — and this one only moves while it is genuinely being
     * spent, so a reader who looks at it after a day away sees exactly the
     * number they left.
     */
    fun countdown(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val m = safe / 60
        val s = safe % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}

package com.pagetime.app.blocker

import com.pagetime.app.domain.GateState

/**
 * What the block screen says.
 *
 * A DIFFERENT SENTENCE, NOT A DIFFERENT TONE
 *
 * The old screen said "Time is up!", which is the correct sentence for a
 * currency: something was spent, and now there is none. It is the wrong
 * sentence for a gate, where nothing was spent and nothing ran out — the day's
 * reading simply has not been done yet. Under the gate the screen reports a
 * DISTANCE ("1h 12m of 2h") rather than a debt, because a distance is a thing
 * the reader can close and a debt is a thing that happened to them.
 *
 * Kept out of the overlay class so the wording and the arithmetic can be
 * tested without a WindowManager, which is the only reason anything about the
 * block screen has ever been checkable.
 */
object BlockScreenText {

    /** Whether to draw the progress bar, and how full. Null on the balance. */
    fun progress(gate: GateState): Float? = if (gate.enabled) gate.progress else null

    fun title(gate: GateState): String =
        if (!gate.enabled) "Time is up!"
        else "${span(gate.accruedSeconds)} of ${span(gate.thresholdSeconds)}"

    fun subtitle(gate: GateState): String = when {
        !gate.enabled -> "Read a few minutes to earn time in this app."
        gate.remainingSeconds <= 0 -> "Reading done. This app is open."
        else -> "${span(gate.remainingSeconds)} of reading left before your apps open."
    }

    /**
     * A duration a person would say out loud.
     *
     * Never seconds: the smallest thing this screen ever reports is a minute
     * of reading, and "1h 11m 58s" invites watching a number rather than
     * reading. Anything under a minute still has to say something, and "less
     * than a minute" is the honest version of a zero that is not zero.
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

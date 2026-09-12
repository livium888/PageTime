package com.pagetime.app.ui.screens.review

import java.time.Duration
import java.time.Instant

/** Formats the exact local time at which a reviewed card will become due again. */
internal fun formatNextReview(
    nextDue: Instant,
    now: Instant = Instant.now(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
): String {
    val due = nextDue.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = due.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

    return when (due.toLocalDate()) {
        today -> "today at $time"
        today.plusDays(1) -> "tomorrow at $time"
        else -> due.format(
            java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM,
                java.time.format.FormatStyle.SHORT
            )
        )
    }
}

/**
 * Anki-style compact interval label for a review button: "10m", "2d", "1.2mo".
 * Mirrors Anki's button captions ("Good 2d") so you can see the next review
 * time before you press it.
 */
internal fun formatIntervalShort(duration: Duration): String {
    val minutes = duration.toMinutes()
    return when {
        minutes < 60L -> "${minutes}m"
        minutes < 24L * 60L -> {
            val hours = duration.toHours()
            val rem = minutes % 60L
            if (rem >= 30L) "${hours + 1}h" else "${hours}h"
        }
        minutes < 30L * 24L * 60L -> {
            val days = duration.toDays()
            val remMinutes = minutes % (24L * 60L)
            if (remMinutes >= 12L * 60L) "${days + 1}d" else "${days}d"
        }
        else -> {
            val months = duration.toDays() / 30L
            val remDays = duration.toDays() % 30L
            if (remDays >= 15L) "${months + 1}mo" else "${months}mo"
        }
    }
}

/** Overload for Instant-based schedules. */
internal fun formatIntervalShort(nextDue: Instant, now: Instant): String =
    formatIntervalShort(Duration.between(now, nextDue))

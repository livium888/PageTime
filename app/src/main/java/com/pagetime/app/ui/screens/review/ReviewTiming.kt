package com.pagetime.app.ui.screens.review

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Formats the exact local time at which a reviewed card will become due again. */
internal fun formatNextReview(
    nextDue: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val due = nextDue.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = due.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    return when (due.toLocalDate()) {
        today -> "today at $time"
        today.plusDays(1) -> "tomorrow at $time"
        else -> due.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}

/**
 * Anki-style compact interval caption for a rating button: "10m", "2d", "2mo".
 * Mirrors Anki's button captions ("Good 2d") so the reader sees the next
 * review time before pressing it.
 */
internal fun formatIntervalShort(duration: Duration): String {
    val minutes = duration.toMinutes()
    return when {
        minutes < 60L -> "${minutes}m"
        minutes < 24L * 60L -> {
            val hours = duration.toHours()
            if (minutes % 60L >= 30L) "${hours + 1}h" else "${hours}h"
        }
        minutes < 30L * 24L * 60L -> {
            val days = duration.toDays()
            if (minutes % (24L * 60L) >= 12L * 60L) "${days + 1}d" else "${days}d"
        }
        else -> {
            val months = duration.toDays() / 30L
            if (duration.toDays() % 30L >= 15L) "${months + 1}mo" else "${months}mo"
        }
    }
}

/** Overload for Instant-based schedules. */
internal fun formatIntervalShort(nextDue: Instant, now: Instant): String =
    formatIntervalShort(Duration.between(now, nextDue))

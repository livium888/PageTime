package com.pagetime.app.ui.screens.review

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

package com.pagetime.app.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The reading lock's day, which starts at [RESET_HOUR] local time, not midnight.
 *
 * WALL-CLOCK ARITHMETIC, ON PURPOSE
 *
 * The day is computed by shifting the LOCAL date-time back by [RESET_HOUR]
 * hours, not by subtracting hours from the instant. On a daylight-saving
 * change the two differ by an hour, and only the local version keeps the
 * reset at 04:00 on the clock on the wall — which is the only reset time a
 * person will ever think about.
 *
 * Days are epoch-day numbers, the same unit [ReadingStreak] and
 * [DailyEarningCap] use, so a stored day compares with `==` and nothing else.
 */
object LockDay {

    /** 04:00, so reading in bed after midnight still counts for that evening. */
    const val RESET_HOUR = 4

    /** The lock day [epochMillis] falls in, as an epoch day. */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            .minusHours(RESET_HOUR.toLong())
            .toLocalDate()
            .toEpochDay()

    /**
     * When the next reset happens, as epoch millis.
     *
     * Resolved through the zone rules, so a reset that falls in a DST gap moves
     * to the first valid instant after it rather than vanishing.
     */
    fun nextResetAt(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val resetToday = local.toLocalDate().atTime(LocalTime.of(RESET_HOUR, 0))
        val next = if (local < resetToday) resetToday else resetToday.plusDays(1)
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Reading counted for [today], given what was stored and for which day.
     *
     * A tally stored on another day is yesterday's (or older) and counts for
     * nothing — that is the whole reset, and it needs no job to run at 04:00.
     */
    fun readToday(storedDay: Long, storedSeconds: Long, today: Long): Long =
        if (storedDay == today) storedSeconds.coerceAtLeast(0L) else 0L
}

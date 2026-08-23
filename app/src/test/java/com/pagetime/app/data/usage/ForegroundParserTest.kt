package com.pagetime.app.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the anti-cheat audit layer's pure core: it must reconstruct,
 * from raw usage events, exactly how long a blocked app was on screen with the
 * screen interactive. These tests pin down: stacking of packages, screen-off
 * gaps, keyguard, window clamping, and the "starting state" heuristic.
 */
class ForegroundParserTest {

    private val parser = ForegroundParser()
    private val blocked = setOf("com.instagram", "com.twitter.android")

    private fun fg(pkg: String, type: Int, t: Long) = UsageEventSample(pkg, type, t)

    private val F = ForegroundParser.EVENT_FOREGROUND
    private val B = ForegroundParser.EVENT_BACKGROUND
    private val ON = ForegroundParser.EVENT_SCREEN_INTERACTIVE
    private val OFF = ForegroundParser.EVENT_SCREEN_NON_INTERACTIVE

    private fun min(s: Int) = s * 60_000L

    @Test
    fun `simple foreground interval counts screen-on time`() {
        val events = listOf(
            fg("com.instagram", F, min(1)),
            fg("com.instagram", B, min(5))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(6))
        assertEquals(min(4), result["com.instagram"])
    }

    @Test
    fun `screen off inside foreground interval is not counted`() {
        val events = listOf(
            fg("com.instagram", F, min(1)),
            fg("", OFF, min(2)),
            fg("", ON, min(3)),
            fg("com.instagram", B, min(4))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(5))
        // 1→2 (1 min) + 3→4 (1 min); the 2→3 off minute is free.
        assertEquals(2 * 60_000L, result["com.instagram"])
    }

    @Test
    fun `keyguard shown counts as non-interactive`() {
        val events = listOf(
            fg("com.twitter.android", F, min(1)),
            fg("", KEYGUARD_SHOWN, min(2)),
            fg("", KEYGUARD_HIDDEN, min(3)),
            fg("com.twitter.android", B, min(4))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(5))
        assertEquals(2 * 60_000L, result["com.twitter.android"])
    }

    @Test
    fun `two packages stack independently`() {
        val events = listOf(
            fg("com.instagram", F, min(1)),
            fg("com.twitter.android", F, min(1) + 30_000),
            fg("com.instagram", B, min(2)),
            fg("com.twitter.android", B, min(2) + 30_000)
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(3))
        assertEquals(60_000L, result["com.instagram"])
        assertEquals(60_000L, result["com.twitter.android"])
    }

    @Test
    fun `results exclude packages not in the blocked set`() {
        val events = listOf(
            fg("com.chrome", F, min(1)),
            fg("com.chrome", B, min(2))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(3))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `window clamps partial intervals at the boundaries`() {
        // Session starts before `from`, ends after `to`.
        val events = listOf(
            fg("com.instagram", F, min(0) - 30_000),
            fg("com.instagram", B, min(5) + 30_000)
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(5))
        assertEquals(5 * 60_000L, result["com.instagram"])
    }

    @Test
    fun `started with screen off gets no charge until interactive event`() {
        val events = listOf(
            fg("", OFF, min(1)), // first screen event: off → assume window started off
            fg("com.instagram", F, min(2)), // opened while screen off (keyguard etc.)
            fg("", ON, min(3)),
            fg("com.instagram", B, min(4))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(5))
        assertEquals(60_000L, result["com.instagram"]) // only 3→4 charged
    }

    @Test
    fun `background before any foreground is ignored`() {
        val events = listOf(
            fg("com.instagram", B, min(1))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(2))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `persistent foreground across whole window counts the full window`() {
        val events = listOf(
            fg("com.instagram", F, min(0))
        )
        val result = parser.screenOnForegroundMillis(events, blocked, min(0), min(10))
        assertEquals(10 * 60_000L, result["com.instagram"])
    }

    private val KEYGUARD_SHOWN = ForegroundParser.EVENT_KEYGUARD_SHOWN
    private val KEYGUARD_HIDDEN = ForegroundParser.EVENT_KEYGUARD_HIDDEN
}
package com.pagetime.app.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val result = parser.scan(events, blocked, min(0), min(6)).millisByPackage
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
        val result = parser.scan(events, blocked, min(0), min(5)).millisByPackage
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
        val result = parser.scan(events, blocked, min(0), min(5)).millisByPackage
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
        val result = parser.scan(events, blocked, min(0), min(3)).millisByPackage
        assertEquals(60_000L, result["com.instagram"])
        assertEquals(60_000L, result["com.twitter.android"])
    }

    @Test
    fun `results exclude packages not in the blocked set`() {
        val events = listOf(
            fg("com.chrome", F, min(1)),
            fg("com.chrome", B, min(2))
        )
        val result = parser.scan(events, blocked, min(0), min(3)).millisByPackage
        assertTrue(result.isEmpty())
    }

    @Test
    fun `window clamps partial intervals at the boundaries`() {
        // Session starts before `from`, ends after `to`.
        val events = listOf(
            fg("com.instagram", F, min(0) - 30_000),
            fg("com.instagram", B, min(5) + 30_000)
        )
        val result = parser.scan(events, blocked, min(0), min(5)).millisByPackage
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
        val result = parser.scan(events, blocked, min(0), min(5)).millisByPackage
        assertEquals(60_000L, result["com.instagram"]) // only 3→4 charged
    }

    @Test
    fun `background before any foreground is ignored`() {
        val events = listOf(
            fg("com.instagram", B, min(1))
        )
        val result = parser.scan(events, blocked, min(0), min(2)).millisByPackage
        assertTrue(result.isEmpty())
    }

    @Test
    fun `persistent foreground across whole window counts the full window`() {
        val events = listOf(
            fg("com.instagram", F, min(0))
        )
        val result = parser.scan(events, blocked, min(0), min(10)).millisByPackage
        assertEquals(10 * 60_000L, result["com.instagram"])
    }

    // --- Carrying state between windows ---
    //
    // The regression these exist for: Android emits a foreground event once,
    // when an app is opened. A reader who opens Kindle and reads for ten
    // minutes produces ONE event, in the first sweep window. Every window after
    // it contains no events at all, and a scan that rebuilt its state from each
    // window alone measured them as zero — so ten minutes of reading logged as
    // roughly one. These pin the fix.

    @Test
    fun `a window with no events at all still counts a carried-in sitting`() {
        val result = parser.scan(
            events = emptyList(),
            trackedPackages = blocked,
            from = min(0),
            to = min(1),
            startState = ForegroundState(openPackages = setOf("com.instagram")),
        )
        assertEquals(min(1), result.millisByPackage["com.instagram"])
    }

    @Test
    fun `ten one-minute windows of uninterrupted reading measure ten minutes`() {
        // One foreground event, in the first window, and nothing after it.
        var state = ForegroundState()
        var total = 0L
        for (window in 0 until 10) {
            val events = if (window == 0) listOf(fg("com.instagram", F, min(0))) else emptyList()
            val scan = parser.scan(
                events = events,
                trackedPackages = blocked,
                from = min(window),
                to = min(window + 1),
                startState = state,
            )
            total += scan.millisByPackage["com.instagram"] ?: 0L
            state = scan.endState
        }
        assertEquals(min(10), total)
    }

    @Test
    fun `closing state reports what is still open and the screen state`() {
        val open = parser.scan(
            listOf(fg("com.instagram", F, min(1))), blocked, min(0), min(2)
        ).endState
        assertEquals(setOf("com.instagram"), open.openPackages)
        assertTrue(open.interactive)

        val closed = parser.scan(
            listOf(fg("com.instagram", F, min(1)), fg("com.instagram", B, min(2))),
            blocked, min(0), min(3)
        ).endState
        assertTrue(closed.openPackages.isEmpty())

        val asleep = parser.scan(listOf(fg("", OFF, min(1))), blocked, min(0), min(2)).endState
        assertFalse(asleep.interactive)
    }

    @Test
    fun `a carried-in sitting accrues nothing while the screen is off`() {
        val result = parser.scan(
            events = listOf(fg("", ON, min(3))),
            trackedPackages = blocked,
            from = min(0),
            to = min(4),
            startState = ForegroundState(
                openPackages = setOf("com.instagram"),
                interactive = false,
            ),
        )
        // Asleep 0→3, awake 3→4.
        assertEquals(min(1), result.millisByPackage["com.instagram"])
    }

    @Test
    fun `state is tracked for untracked packages so trusting one mid-sitting works`() {
        // Nothing tracked yet, but the sitting must still be followed.
        val first = parser.scan(
            events = listOf(fg("com.kindle", F, min(0))),
            trackedPackages = emptySet(),
            from = min(0),
            to = min(1),
        )
        assertTrue(first.millisByPackage.isEmpty())
        assertEquals(setOf("com.kindle"), first.endState.openPackages)

        // Now trusted — the next window pays without waiting for a reopen.
        val second = parser.scan(
            events = emptyList(),
            trackedPackages = setOf("com.kindle"),
            from = min(1),
            to = min(2),
            startState = first.endState,
        )
        assertEquals(min(1), second.millisByPackage["com.kindle"])
    }

    // --- Persisted form ---

    @Test
    fun `state survives a round trip through its stored form`() {
        val state = ForegroundState(setOf("com.instagram", "com.kindle"), interactive = false)
        val restored = ForegroundState.decode(state.encode())
        assertEquals(state.openPackages, restored.openPackages)
        assertEquals(state.interactive, restored.interactive)

        val empty = ForegroundState.decode(ForegroundState().encode())
        assertTrue(empty.openPackages.isEmpty())
        assertTrue(empty.interactive)
    }

    @Test
    fun `unreadable stored state falls back to nothing open`() {
        listOf(null, "", "garbage-with-no-separator").forEach {
            val decoded = ForegroundState.decode(it)
            assertTrue(decoded.openPackages.isEmpty())
            assertTrue(decoded.interactive)
        }
    }

    private val KEYGUARD_SHOWN = ForegroundParser.EVENT_KEYGUARD_SHOWN
    private val KEYGUARD_HIDDEN = ForegroundParser.EVENT_KEYGUARD_HIDDEN
}
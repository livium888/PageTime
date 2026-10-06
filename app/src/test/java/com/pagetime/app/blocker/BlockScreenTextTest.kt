package com.pagetime.app.blocker

import com.pagetime.app.domain.GateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockScreenTextTest {

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val TARGET = GateState.DEFAULT_DAILY_TARGET_SECONDS // 20m
    }

    private fun gate(
        read: Long = 0,
        switchedOn: Boolean = true,
        target: Long = TARGET,
    ) = GateState(
        switchedOn = switchedOn,
        readTodaySeconds = read,
        disableAtMillis = 0,
        nowMillis = T0,
        dailyTargetSeconds = target,
    )

    @Test
    fun `on the balance the screen still says what it always said`() {
        val g = gate(switchedOn = false)
        assertEquals("Time is up!", BlockScreenText.title(g))
        assertEquals("Read a few minutes to earn time in this app.", BlockScreenText.subtitle(g))
        assertNull(BlockScreenText.progress(g))
    }

    /** A distance the reader can close, not a debt that happened to them. */
    @Test
    fun `part way there the screen reports a distance`() {
        val g = gate(read = 12 * 60)
        assertEquals("12m of 20m read today", BlockScreenText.title(g))
        assertEquals("Read 8m more to unlock your phone until 4am.", BlockScreenText.subtitle(g))
        assertEquals(0.6f, BlockScreenText.progress(g)!!, 0.001f)
    }

    /**
     * The second before the phone opens must not claim to be open — a screen
     * that says you are done and will not let you through is the worst thing
     * it could do.
     */
    @Test
    fun `almost done does not claim to be done`() {
        val g = gate(read = TARGET - 1)
        assertEquals("19m of 20m read today", BlockScreenText.title(g))
        assertEquals("Read under a minute more to unlock your phone until 4am.", BlockScreenText.subtitle(g))
    }

    @Test
    fun `once the reading is done the screen says so`() {
        val g = gate(read = TARGET + 600)
        assertEquals("20m of 20m read today", BlockScreenText.title(g))
        assertEquals("Today's reading is done. Your phone is open until 4am.", BlockScreenText.subtitle(g))
        assertEquals(1f, BlockScreenText.progress(g)!!, 0.001f)
    }

    @Test
    fun `durations read the way a person would say them`() {
        assertEquals("0m", BlockScreenText.span(0))
        assertEquals("under a minute", BlockScreenText.span(30))
        assertEquals("1m", BlockScreenText.span(60))
        assertEquals("59m", BlockScreenText.span(59 * 60))
        assertEquals("1h", BlockScreenText.span(60 * 60))
        assertEquals("1h 1m", BlockScreenText.span(61 * 60))
        assertEquals("2h", BlockScreenText.span(7200))
    }

    // --- The emergency hatch ---

    /**
     * The button names the app, because naming it is the honest description
     * of what happens: one app opens and the rest stay shut.
     */
    @Test
    fun `the emergency button says which app it opens`() {
        assertEquals("Open Monzo for 5 minutes", BlockScreenText.emergencyLabel("Monzo"))
        assertEquals("Open this app for 5 minutes", BlockScreenText.emergencyLabel(null))
        assertEquals("Open this app for 5 minutes", BlockScreenText.emergencyLabel("  "))
    }

    @Test
    fun `the note says what is left and repeats the scope`() {
        assertEquals("2 left this week. Only this app opens.", BlockScreenText.emergencyNote(2, null))
        assertEquals("1 left this week. Only this app opens.", BlockScreenText.emergencyNote(1, null))
    }

    @Test
    fun `with none left it says when the next one is back`() {
        assertEquals(
            "None left. The next one is back in 3h 20m.",
            BlockScreenText.emergencyNote(0, 3 * 3600 + 20 * 60),
        )
        assertEquals("None left this week.", BlockScreenText.emergencyNote(0, null))
    }

    @Test
    fun `a stored target of zero cannot divide the progress by zero`() {
        val g = gate(read = 100, target = 0)
        // Clamped to the 5-minute minimum rather than dividing by zero.
        assertEquals("1m of 5m read today", BlockScreenText.title(g))
        assertEquals(100f / 300f, BlockScreenText.progress(g)!!, 0.001f)
    }

    // --- Site subtitle: the one text that differs by SiteMode ---

    @Test
    fun `blocklist subtitle names the reader's own rule`() {
        val whole = SiteRules.Rule("bbc.co.uk", null)
        val section = SiteRules.Rule("bbc.co.uk", "/news")
        assertEquals(
            "You put this site off limits. Go back, or read for a while.",
            BlockScreenText.siteSubtitle(whole, SiteMode.BLOCKLIST),
        )
        assertEquals(
            "You put this part of the site off limits. Go back, or read for a while.",
            BlockScreenText.siteSubtitle(section, SiteMode.BLOCKLIST),
        )
    }

    @Test
    fun `allowlist subtitle says nothing let it through, not that it was blocked`() {
        val notAllowed = SiteRules.Rule("example.com", null)
        assertEquals(
            "This isn't on your allowed list. Go back, or read for a while.",
            BlockScreenText.siteSubtitle(notAllowed, SiteMode.ALLOWLIST),
        )
    }
}

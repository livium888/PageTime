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
        const val COST = GateState.DEFAULT_SESSION_COST_SECONDS
    }

    private fun gate(
        credit: Long = 0,
        switchedOn: Boolean = true,
        sessionEndsAt: Long = 0,
    ) = GateState(
        switchedOn = switchedOn,
        creditSeconds = credit,
        sessionEndsAtMillis = sessionEndsAt,
        disableAtMillis = 0,
        nowMillis = T0,
    )

    @Test
    fun `on the balance the screen still says what it always said`() {
        val g = gate(switchedOn = false)
        assertEquals("Time is up!", BlockScreenText.title(g))
        assertEquals("Read a few minutes to earn time in this app.", BlockScreenText.subtitle(g))
        assertNull(BlockScreenText.progress(g))
        assertFalse(BlockScreenText.showsStartButton(g))
    }

    /** A distance the reader can close, not a debt that happened to them. */
    @Test
    fun `part way there the screen reports a distance`() {
        val g = gate(credit = 72 * 60)
        assertEquals("1h 12m of 2h", BlockScreenText.title(g))
        assertEquals("48m of reading before your next 30m.", BlockScreenText.subtitle(g))
        assertEquals(0.6f, BlockScreenText.progress(g)!!, 0.001f)
        assertFalse(BlockScreenText.showsStartButton(g))
    }

    /**
     * The one moment the block screen is a door rather than a wall. Getting
     * this wrong would leave a reader who has done the work staring at a
     * refusal with no way through.
     */
    @Test
    fun `once the reading is done the screen offers the session`() {
        val g = gate(credit = COST)
        assertTrue(BlockScreenText.showsStartButton(g))
        assertEquals("You've read enough", BlockScreenText.title(g))
        assertEquals("Start your 30m whenever you are ready.", BlockScreenText.subtitle(g))
        assertEquals("Start 30m", BlockScreenText.startButtonLabel(g))
    }

    @Test
    fun `a banked session still offers itself rather than showing a full bar as done`() {
        val g = gate(credit = COST + 30 * 60)
        assertTrue(BlockScreenText.showsStartButton(g))
    }

    /**
     * The second before the door opens must not claim to be open — a screen
     * that says you are done and will not let you through is the worst thing
     * it could do.
     */
    @Test
    fun `almost done does not claim to be done`() {
        val g = gate(credit = COST - 1)
        assertFalse(BlockScreenText.showsStartButton(g))
        assertEquals("1h 59m of 2h", BlockScreenText.title(g))
        assertEquals("under a minute of reading before your next 30m.", BlockScreenText.subtitle(g))
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

    /** While something is running out, the seconds are the information. */
    @Test
    fun `the session countdown shows seconds`() {
        assertEquals("30:00", BlockScreenText.countdown(1800))
        assertEquals("9:05", BlockScreenText.countdown(545))
        assertEquals("0:09", BlockScreenText.countdown(9))
        assertEquals("0:00", BlockScreenText.countdown(0))
        assertEquals("0:00", BlockScreenText.countdown(-5))
    }

    @Test
    fun `a zero cost cannot divide the title by zero`() {
        val g = GateState(
            switchedOn = true,
            creditSeconds = 100,
            sessionEndsAtMillis = 0,
            disableAtMillis = 0,
            nowMillis = T0,
            sessionCostSeconds = 0,
        )
        // Affordable at any credit, so it offers rather than dividing.
        assertTrue(BlockScreenText.showsStartButton(g))
        assertEquals("You've read enough", BlockScreenText.title(g))
    }
}

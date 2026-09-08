package com.pagetime.app.blocker

import com.pagetime.app.domain.GateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockScreenTextTest {

    private fun gate(reading: Long, threshold: Long = 7200, enabled: Boolean = true) =
        GateState(
            enabled = enabled,
            readingSeconds = reading,
            planningSeconds = 0,
            thresholdSeconds = threshold,
            planningCapSeconds = 1200,
        )

    @Test
    fun `on the balance the screen still says what it always said`() {
        val g = gate(reading = 0, enabled = false)
        assertEquals("Time is up!", BlockScreenText.title(g))
        assertEquals("Read a few minutes to earn time in this app.", BlockScreenText.subtitle(g))
        assertNull(BlockScreenText.progress(g))
    }

    /** A distance the reader can close, not a debt that happened to them. */
    @Test
    fun `under the gate the screen reports a distance`() {
        val g = gate(reading = 72 * 60)
        assertEquals("1h 12m of 2h", BlockScreenText.title(g))
        assertEquals("48m of reading left before your apps open.", BlockScreenText.subtitle(g))
        assertEquals(0.6f, BlockScreenText.progress(g)!!, 0.001f)
    }

    @Test
    fun `counted planning shows up in the distance`() {
        val g = GateState(
            enabled = true,
            readingSeconds = 60 * 60,
            planningSeconds = 20 * 60,
            thresholdSeconds = 7200,
            planningCapSeconds = 1200,
        )
        assertEquals("1h 20m of 2h", BlockScreenText.title(g))
    }

    @Test
    fun `an open gate says so rather than counting`() {
        assertEquals("Reading done. This app is open.", BlockScreenText.subtitle(gate(reading = 7200)))
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
        assertEquals("8h", BlockScreenText.span(8 * 3600))
    }

    /**
     * The second before the gate opens must not round up to "2h of 2h" while
     * still refusing entry — a screen that says you are done and will not let
     * you through is the worst thing this screen could do.
     */
    @Test
    fun `almost done does not claim to be done`() {
        val g = gate(reading = 7200 - 1)
        assertEquals("1h 59m of 2h", BlockScreenText.title(g))
        assertEquals("under a minute of reading left before your apps open.", BlockScreenText.subtitle(g))
    }
}

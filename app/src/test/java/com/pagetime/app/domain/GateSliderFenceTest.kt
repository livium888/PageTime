package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fence on the session terms.
 *
 * Outside a session the reader may make their own terms harder but not
 * easier. The interaction that made an earlier version of this unusable is
 * described on [GateState.costBounds]; these tests cover the rule, and the
 * last two cover the shape the control needs so that the rule cannot be
 * expressed as something the screen will crash on.
 */
class GateSliderFenceTest {

    private val twoHours = 2L * 60 * 60
    private val halfHour = 30L * 60

    @Test
    fun `with a session in hand the price moves anywhere`() {
        val bounds = GateState.costBounds(twoHours, canLoosen = true)
        assertEquals(GateState.MIN_SESSION_COST_SECONDS, bounds.first)
        assertEquals(GateState.MAX_SESSION_COST_SECONDS, bounds.last)
    }

    @Test
    fun `without one the price may only go up`() {
        val bounds = GateState.costBounds(twoHours, canLoosen = false)
        assertEquals(twoHours, bounds.first)
        assertEquals(GateState.MAX_SESSION_COST_SECONDS, bounds.last)
    }

    @Test
    fun `without one the session may only get shorter`() {
        val bounds = GateState.lengthBounds(halfHour, canLoosen = false)
        assertEquals(GateState.MIN_SESSION_LENGTH_SECONDS, bounds.first)
        assertEquals(halfHour, bounds.last)
    }

    @Test
    fun `with a session in hand the length moves anywhere`() {
        val bounds = GateState.lengthBounds(halfHour, canLoosen = true)
        assertEquals(GateState.MIN_SESSION_LENGTH_SECONDS, bounds.first)
        assertEquals(GateState.MAX_SESSION_LENGTH_SECONDS, bounds.last)
    }

    @Test
    fun `cheaper is loosening, dearer is not`() {
        assertTrue(GateState.loosensCost(twoHours, twoHours - 60))
        assertFalse(GateState.loosensCost(twoHours, twoHours + 60))
        assertFalse(GateState.loosensCost(twoHours, twoHours))
    }

    @Test
    fun `longer is loosening, shorter is not`() {
        assertTrue(GateState.loosensLength(halfHour, halfHour + 60))
        assertFalse(GateState.loosensLength(halfHour, halfHour - 60))
        assertFalse(GateState.loosensLength(halfHour, halfHour))
    }

    @Test
    fun `the current value is always inside its own bounds`() {
        // The invariant the screen depends on: whatever is stored, the thumb
        // has somewhere legal to sit.
        for (seconds in listOf(0L, 60L, twoHours, GateState.MAX_SESSION_COST_SECONDS, Long.MAX_VALUE / 4)) {
            for (loosen in listOf(true, false)) {
                val cost = GateState.costBounds(seconds, loosen)
                val clampedCost = seconds.coerceIn(
                    GateState.MIN_SESSION_COST_SECONDS,
                    GateState.MAX_SESSION_COST_SECONDS,
                )
                assertTrue("cost $seconds loosen=$loosen", clampedCost in cost)
                assertTrue("cost not inverted $seconds", cost.last >= cost.first)

                val length = GateState.lengthBounds(seconds, loosen)
                val clampedLength = seconds.coerceIn(
                    GateState.MIN_SESSION_LENGTH_SECONDS,
                    GateState.MAX_SESSION_LENGTH_SECONDS,
                )
                assertTrue("length $seconds loosen=$loosen", clampedLength in length)
                assertTrue("length not inverted $seconds", length.last >= length.first)
            }
        }
    }

    @Test
    fun `a value already at the strict end leaves no travel`() {
        // Pinned at the ceiling with no session: there is nowhere legal to go,
        // and the screen switches the control off rather than handing a
        // Slider a range of zero width.
        val cost = GateState.costBounds(GateState.MAX_SESSION_COST_SECONDS, canLoosen = false)
        assertFalse(GateState.hasTravel(cost))

        val length = GateState.lengthBounds(GateState.MIN_SESSION_LENGTH_SECONDS, canLoosen = false)
        assertFalse(GateState.hasTravel(length))
    }

    @Test
    fun `a value in the middle always has travel`() {
        assertTrue(GateState.hasTravel(GateState.costBounds(twoHours, canLoosen = false)))
        assertTrue(GateState.hasTravel(GateState.lengthBounds(halfHour, canLoosen = false)))
        assertTrue(GateState.hasTravel(GateState.costBounds(twoHours, canLoosen = true)))
    }

    @Test
    fun `a live session unfences both controls`() {
        val inSession = GateState(
            switchedOn = true,
            creditSeconds = 0,
            sessionSecondsRemaining = 600,
            disableAtMillis = 0,
            nowMillis = 1_000,
        )
        assertTrue(inSession.canLoosenTheRules)

        val outside = inSession.copy(sessionSecondsRemaining = 0)
        assertFalse(outside.canLoosenTheRules)
    }

    @Test
    fun `a gate that is off fences nothing`() {
        val off = GateState(
            switchedOn = false,
            creditSeconds = 0,
            sessionSecondsRemaining = 0,
            disableAtMillis = 0,
            nowMillis = 1_000,
        )
        assertTrue(off.canLoosenTheRules)
    }
}

package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpanMarkerTest {

    private val start = SpanPoint(2, 0.3f, chapterTitle = "Three")
    private val end = SpanPoint(4, 0.6f, chapterTitle = "Five")

    @Test
    fun `a marker survives storage`() {
        val m = SpanMarker(start, setAt = 1_000L)
        assertEquals(m, SpanMarker.decode(m.encode()))
    }

    @Test
    fun `a copied-but-not-yet-imported span survives storage with its nudge`() {
        val m = SpanMarker(start, setAt = 1_000L).withPending(end, adjust = -2)
        val back = SpanMarker.decode(m.encode())
        assertNotNull(back)
        assertEquals(end, back!!.pendingEnd)
        assertEquals(-2, back.pendingAdjust)
        assertEquals(start, back.start)
    }

    @Test
    fun `importing moves the start to where the span ended and clears what was pending`() {
        val copied = SpanMarker(start, setAt = 1_000L).withPending(end, adjust = 1)
        val next = copied.advancedTo(end, now = 5_000L)
        assertEquals(end, next.start)
        assertEquals(5_000L, next.setAt)
        assertNull(next.pendingEnd)
        assertEquals(0, next.pendingAdjust)
    }

    @Test
    fun `a missing or damaged stored value is no marker rather than a crash`() {
        assertNull(SpanMarker.decode(null))
        assertNull(SpanMarker.decode(""))
        assertNull(SpanMarker.decode("not json"))
        assertNull(SpanMarker.decode("""{"setAt": 5}"""))
    }
}

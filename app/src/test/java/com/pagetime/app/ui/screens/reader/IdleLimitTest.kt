package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleLimitTest {

    private val floor = 120_000L

    @Test
    fun `a thin page keeps the usual limit`() {
        assertEquals(floor, IdleLimit.forWords(0, floor))
        assertEquals(floor, IdleLimit.forWords(100, floor))
    }

    @Test
    fun `a dense page is given longer, because a slow careful reader needs it`() {
        // 350 words at 80 a minute is 262 seconds, and 30 seconds of grace.
        val limit = IdleLimit.forWords(350, floor)
        assertTrue(limit > 280_000L && limit < 300_000L)
    }

    @Test
    fun `it never grows past five minutes, so a phone left on a table is still caught`() {
        assertEquals(300_000L, IdleLimit.forWords(5_000, floor))
    }

    @Test
    fun `a floor above the ceiling is kept`() {
        assertEquals(400_000L, IdleLimit.forWords(5_000, 400_000L))
    }
}

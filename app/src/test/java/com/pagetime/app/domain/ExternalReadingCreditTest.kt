package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalReadingCreditTest {

    @Test
    fun `half of the measured foreground time is credited`() {
        assertEquals(30L, ExternalReadingCredit.creditedSeconds(60L))
    }

    @Test
    fun `rounds down rather than crediting a fractional second`() {
        assertEquals(1L, ExternalReadingCredit.creditedSeconds(3L))
    }

    @Test
    fun `zero foreground time credits nothing`() {
        assertEquals(0L, ExternalReadingCredit.creditedSeconds(0L))
    }
}

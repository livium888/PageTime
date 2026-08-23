package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPositionPolicyTest {
    @Test
    fun `position cannot be persisted during restore`() {
        assertFalse(ReaderPositionPolicy.canPersist(false))
        assertTrue(ReaderPositionPolicy.canPersist(true))
    }

    @Test
    fun `fraction is bounded before persistence`() {
        assertEquals(0f, ReaderPositionPolicy.clampFraction(-0.4f))
        assertEquals(0.35f, ReaderPositionPolicy.clampFraction(0.35f))
        assertEquals(1f, ReaderPositionPolicy.clampFraction(1.4f))
    }
}

package com.pagetime.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMomentumTest {

    @Test
    fun `the lowest possible draw returns the minimum interval`() {
        assertEquals(180L, ReadingMomentum.nextThresholdSeconds { 0.0 })
    }

    @Test
    fun `the highest possible draw stays under the maximum interval`() {
        val threshold = ReadingMomentum.nextThresholdSeconds { 0.999999 }
        assertTrue(threshold in 180L until 420L)
    }

    @Test
    fun `a draw outside 0 to 1 is clamped rather than escaping the range`() {
        assertEquals(180L, ReadingMomentum.nextThresholdSeconds { -5.0 })
        assertTrue(ReadingMomentum.nextThresholdSeconds { 5.0 } < 420L + 1L)
    }

    @Test
    fun `does not fire before enough credited reading has piled up`() {
        assertFalse(ReadingMomentum.shouldFire(creditedSecondsSinceLastBonus = 179L, threshold = 180L))
    }

    @Test
    fun `fires the moment credited reading reaches the threshold`() {
        assertTrue(ReadingMomentum.shouldFire(creditedSecondsSinceLastBonus = 180L, threshold = 180L))
    }

    @Test
    fun `still fires if a caller checks late, past the threshold`() {
        assertTrue(ReadingMomentum.shouldFire(creditedSecondsSinceLastBonus = 250L, threshold = 180L))
    }
}

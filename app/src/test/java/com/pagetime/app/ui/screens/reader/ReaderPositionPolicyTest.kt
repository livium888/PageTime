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

    @Test
    fun `a place is remembered as a fraction of the item, not a number of pixels`() {
        assertEquals(0.5f, ReaderPositionPolicy.fractionOf(offset = 800, itemHeight = 1_600))
        assertEquals(800, ReaderPositionPolicy.offsetFor(0.5f, itemHeight = 1_600f))
        assertEquals(400, ReaderPositionPolicy.offsetFor(0.25f, itemHeight = 1_600f))
    }

    @Test
    fun `turning the phone keeps the reader on the same line of the page`() {
        // The bug this exists for. A page is drawn full-bleed, so a rotation
        // redraws it taller by the same factor its width grew. Portrait: two
        // thousand pixels down a page four thousand tall is halfway. Landscape
        // makes that page nine thousand tall, and restoring two thousand pixels
        // would land the reader a quarter of the way up it instead.
        val fraction = ReaderPositionPolicy.fractionOf(offset = 2_000, itemHeight = 4_000)
        assertEquals(0.5f, fraction)
        assertEquals(4_500, ReaderPositionPolicy.offsetFor(fraction, itemHeight = 9_000f))
    }

    @Test
    fun `an offset past the end of a shorter page still resolves inside it`() {
        // The case that reads as "the reading place is lost": an offset that
        // was inside the old page but past the end of the new one, which lands
        // the reader on a page they had not reached. A fraction cannot escape
        // the item it belongs to.
        val fraction = ReaderPositionPolicy.fractionOf(offset = 1_400, itemHeight = 1_528)
        assertTrue(fraction <= 1f)
        assertEquals(825, ReaderPositionPolicy.offsetFor(fraction, itemHeight = 900f))
    }

    @Test
    fun `an unmeasured item cannot invent a place`() {
        assertEquals(0f, ReaderPositionPolicy.fractionOf(offset = 400, itemHeight = 0))
        assertEquals(0, ReaderPositionPolicy.offsetFor(-1f, itemHeight = 800f))
        assertEquals(800, ReaderPositionPolicy.offsetFor(3f, itemHeight = 800f))
    }
}

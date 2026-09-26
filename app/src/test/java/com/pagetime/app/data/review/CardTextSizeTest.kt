package com.pagetime.app.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the small/medium/large card text setting.
 *
 * The property that matters is that a stored key always comes back as a usable
 * size: a preference is read on a screen the reader cannot get out of without
 * reading it, so an unrecognised value has to degrade to the default rather
 * than throw.
 */
class CardTextSizeTest {

    @Test
    fun `every size round-trips through its stored key`() {
        CardTextSize.entries.forEach { size ->
            assertEquals(size, CardTextSize.fromKey(size.key))
        }
    }

    @Test
    fun `an unknown or missing key falls back to the default`() {
        assertEquals(CardTextSize.DEFAULT, CardTextSize.fromKey(null))
        assertEquals(CardTextSize.DEFAULT, CardTextSize.fromKey(""))
        assertEquals(CardTextSize.DEFAULT, CardTextSize.fromKey("enormous"))
    }

    @Test
    fun `keys are distinct`() {
        val keys = CardTextSize.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `default is the middle of the range, not either end`() {
        assertEquals(CardTextSize.MEDIUM, CardTextSize.DEFAULT)
        assertEquals(1f, CardTextSize.DEFAULT.scale, 0f)
    }

    @Test
    fun `scales increase with the labels`() {
        val ordered = CardTextSize.entries.sortedBy { it.scale }
        assertEquals(listOf(CardTextSize.SMALL, CardTextSize.MEDIUM, CardTextSize.LARGE), ordered)
        assertTrue(CardTextSize.SMALL.scale < 1f)
        assertTrue(CardTextSize.LARGE.scale > 1f)
    }

    @Test
    fun `no size shrinks or grows the card beyond legibility`() {
        // A guard rail rather than a taste: below about four fifths the answer
        // stops being readable on a phone, and past about a third larger the
        // question pushes its own answer off the page the setting exists to fit.
        CardTextSize.entries.forEach { size ->
            assertTrue("${size.label} is too small", size.scale >= 0.8f)
            assertTrue("${size.label} is too large", size.scale <= 1.3f)
        }
    }

    @Test
    fun `every size has a label to show in the menu`() {
        CardTextSize.entries.forEach { size ->
            assertTrue(size.label.isNotBlank())
            assertNull(size.label.firstOrNull { it.isWhitespace() })
        }
    }
}

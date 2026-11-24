package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperVeilRulesTest {

    private val darkBg = 0xFF1E1E1E.toInt()
    private val darkText = 0xFFD5D5D5.toInt()
    private val nightBg = 0xFF000000.toInt()
    private val nightText = 0xFFB0B0B0.toInt()
    private val paperBg = 0xFFF4F1E8.toInt()
    private val paperText = 0xFF2E2A26.toInt()

    @Test
    fun `no warmth leaves colours alone and full warmth takes blue down most`() {
        assertEquals(0xFFFFFFFF.toInt(), PaperVeilRules.tintArgb(0f))
        val full = PaperVeilRules.tintChannels(1f)
        assertEquals(1.0, full[0], 1e-9)
        assertTrue(full[1] < 1.0 && full[2] < full[1])
    }

    @Test
    fun `warming multiplies so black stays black and white only goes cream`() {
        // The old amber overlay turned #000000 into #542F00 and left white/black
        // at about 5:1. Multiplying keeps the black and the gap.
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertTrue(PaperVeilRules.contrast(black, white, 1f, 0f) > 12.0)
        assertEquals(21.0, PaperVeilRules.contrast(black, white, 0f, 0f), 0.01)
    }

    @Test
    fun `contrast is the familiar ratio, and dimming only lowers it`() {
        assertEquals(11.4, PaperVeilRules.contrast(darkBg, darkText, 0f, 0f), 0.1)
        assertEquals(2.5, PaperVeilRules.contrast(darkBg, darkText, 0f, 0.62f), 0.1)
        var last = Double.MAX_VALUE
        for (step in 0..8) {
            val c = PaperVeilRules.contrast(darkBg, darkText, 0.5f, step / 10f)
            assertTrue(c <= last)
            last = c
        }
    }

    @Test
    fun `the dim limit is where text is still readable and not a step further`() {
        listOf(
            Triple(darkBg, darkText, 0f),
            Triple(darkBg, darkText, 1f),
            Triple(nightBg, nightText, 0.5f),
            Triple(paperBg, paperText, 1f),
        ).forEach { (bg, text, warmth) ->
            val max = PaperVeilRules.maxDim(bg, text, warmth)
            assertTrue(max in 0f..0.8f)
            assertTrue(PaperVeilRules.contrast(bg, text, warmth, max) >= PaperVeilRules.MIN_CONTRAST - 0.01)
            if (max < 0.79f) {
                assertTrue(PaperVeilRules.contrast(bg, text, warmth, max + 0.03f) < PaperVeilRules.MIN_CONTRAST)
            }
        }
    }

    @Test
    fun `the Dark theme at 62 percent dim is held back, which it was not before`() {
        val held = PaperVeilRules.effectiveDim(0.62f, darkBg, darkText, 1f)
        assertTrue(held < 0.5f)
        assertTrue(PaperVeilRules.contrast(darkBg, darkText, 1f, held) >= 3.0 - 0.01)
    }

    @Test
    fun `a pair with plenty of contrast gets the full ceiling and an unreadable pair gets none`() {
        assertEquals(0.3f, PaperVeilRules.maxDim(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0f, ceiling = 0.3f), 1e-6f)
        assertEquals(0f, PaperVeilRules.maxDim(0xFF808080.toInt(), 0xFF858585.toInt(), 0f), 1e-6f)
        assertEquals(0f, PaperVeilRules.effectiveDim(0.5f, 0xFF808080.toInt(), 0xFF858585.toInt(), 0f), 1e-6f)
    }

    @Test
    fun `a dim that is small enough is left exactly as chosen`() {
        assertEquals(0.1f, PaperVeilRules.effectiveDim(0.1f, darkBg, darkText, 0f), 1e-6f)
        assertEquals(0f, PaperVeilRules.effectiveDim(-1f, darkBg, darkText, 0f), 1e-6f)
    }

    @Test
    fun `the limit note shows only at a limit lower than the settings ceiling`() {
        assertTrue(PaperVeilRules.atLimit(0.45f, 0.45f))
        assertFalse(PaperVeilRules.atLimit(0.2f, 0.45f))
        assertFalse(PaperVeilRules.atLimit(0.8f, 0.8f))
    }
}

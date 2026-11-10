package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingScaleTest {

    @Test
    fun `a heading never outgrows the line it sits in`() {
        // Lines are fontSize x lineHeight tall. A heading drawn taller than
        // that runs into the line above it.
        for (lineHeight in listOf(1.0f, 1.15f, 1.2f, 1.5f, 1.8f, 2.2f)) {
            val scale = headingScaleFor(lineHeight)
            assertTrue("scale $scale at spacing $lineHeight", scale * 1.15f <= lineHeight + 0.001f || scale == 1f)
        }
    }

    @Test
    fun `tight lines get no enlargement and roomy lines stop at 1_3`() {
        assertEquals(1f, headingScaleFor(1.0f), 0.0001f)
        assertEquals(1f, headingScaleFor(1.15f), 0.0001f)
        assertEquals(1.3f, headingScaleFor(1.5f), 0.01f)
        assertEquals(1.3f, headingScaleFor(2.2f), 0.0001f)
    }
}

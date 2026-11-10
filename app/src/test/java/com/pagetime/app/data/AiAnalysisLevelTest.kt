package com.pagetime.app.data

import com.pagetime.app.data.local.AiAnalysisLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAnalysisLevelTest {

    @Test
    fun `an unknown or missing setting is the lightest level`() {
        assertEquals(AiAnalysisLevel.LIGHT, AiAnalysisLevel.fromKey(null))
        assertEquals(AiAnalysisLevel.LIGHT, AiAnalysisLevel.fromKey("nonsense"))
    }

    @Test
    fun `a stored key reads back as its level`() {
        AiAnalysisLevel.entries.forEach { assertEquals(it, AiAnalysisLevel.fromKey(it.key)) }
    }

    @Test
    fun `a more intensive level looks more often`() {
        assertTrue(AiAnalysisLevel.INTENSIVE.intervalSeconds < AiAnalysisLevel.LIGHT.intervalSeconds)
    }
}

package com.pagetime.app.data

import com.pagetime.app.data.learning.GenerationMode
import com.pagetime.app.data.learning.GenerationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPolicyTest {

    @Test
    fun `on-device first never calls Gemini when local results exist`() {
        assertFalse(
            GenerationPolicy.shouldCallGemini(
                mode = GenerationMode.LOCAL_FIRST,
                localItemCount = 3,
                geminiConfigured = true
            )
        )
    }

    @Test
    fun `on-device first escalates to Gemini only when local results are empty`() {
        assertTrue(
            GenerationPolicy.shouldCallGemini(
                mode = GenerationMode.LOCAL_FIRST,
                localItemCount = 0,
                geminiConfigured = true
            )
        )
        assertFalse(
            GenerationPolicy.shouldCallGemini(
                mode = GenerationMode.LOCAL_FIRST,
                localItemCount = 0,
                geminiConfigured = false
            )
        )
    }

    @Test
    fun `AI-assisted mode prefers Gemini whenever it is configured`() {
        assertTrue(
            GenerationPolicy.shouldCallGemini(
                mode = GenerationMode.GEMINI_FIRST,
                localItemCount = 3,
                geminiConfigured = true
            )
        )
        assertTrue(
            GenerationPolicy.shouldCallGemini(
                mode = GenerationMode.GEMINI_FIRST,
                localItemCount = 0,
                geminiConfigured = true
            )
        )
        assertFalse(
            GenerationPolicy.shouldCallGemini(
                mode = GenerationMode.GEMINI_FIRST,
                localItemCount = 0,
                geminiConfigured = false
            )
        )
    }

    @Test
    fun `unknown or missing mode defaults to AI-assisted`() {
        assertEquals(GenerationMode.GEMINI_FIRST, GenerationMode.fromKey(null))
        assertEquals(GenerationMode.GEMINI_FIRST, GenerationMode.fromKey("bogus"))
        assertEquals(GenerationMode.LOCAL_FIRST, GenerationMode.fromKey("local_first"))
    }
}

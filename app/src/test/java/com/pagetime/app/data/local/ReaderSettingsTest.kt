package com.pagetime.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bounds on the reading comfort settings.
 *
 * One case genuinely matters. The night veil is drawn OVER the page, so a
 * value near 1 renders a black rectangle with the text invisible beneath it —
 * and the slider that would undo it sits behind that same rectangle. The clamp
 * is the only thing between a control and an app that looks broken with no way
 * back.
 */
class ReaderSettingsTest {

    @Test
    fun `night dimming can never hide the page completely`() {
        val absurd = ReaderSettings(nightDim = 1f).normalized()
        assertEquals(ReaderSettings.MAX_NIGHT_DIM, absurd.nightDim, 0.0001f)
        // And a stored negative — from a corrupted preference, or a future
        // caller doing arithmetic — cannot brighten the page either.
        assertEquals(0f, ReaderSettings(nightDim = -0.5f).normalized().nightDim, 0.0001f)
    }

    @Test
    fun `warmth is held inside its range`() {
        assertEquals(1f, ReaderSettings(warmth = 4f).normalized().warmth, 0.0001f)
        assertEquals(0f, ReaderSettings(warmth = -1f).normalized().warmth, 0.0001f)
    }

    @Test
    fun `defaults leave the page exactly as it was`() {
        // Both are opt-in. A reader who never opens the appearance sheet must
        // see no veil at all, or this is a change nobody asked for.
        val defaults = ReaderSettings().normalized()
        assertEquals(0f, defaults.warmth, 0.0001f)
        assertEquals(0f, defaults.nightDim, 0.0001f)
    }

    @Test
    fun `the new paper theme survives a round trip`() {
        // normalized() rejects unknown themes by falling back to light, so a
        // palette added without updating that set would be silently unusable —
        // which is exactly how a theme ships broken.
        assertEquals("paper", ReaderSettings(theme = "paper").normalized().theme)
        assertEquals("light", ReaderSettings(theme = "chartreuse").normalized().theme)
    }
}

package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionPolicyTest {
    @Test
    fun `tap toggles chrome without turning a page`() {
        assertTrue(ReaderInteractionPolicy.togglesChromeOnTap())
        assertFalse(ReaderInteractionPolicy.turnsPageOnEdgeTap())
    }

    @Test
    fun `swipe remains the deliberate page movement gesture`() {
        assertTrue(ReaderInteractionPolicy.turnsPageOnSwipe())
    }
}

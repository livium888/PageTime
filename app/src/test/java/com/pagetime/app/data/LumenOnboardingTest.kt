package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenOnboardingTest {
    @Test
    fun `covers every newcomer action`() {
        assertEquals(
            setOf(
                LumenOnboarding.Action.LINK,
                LumenOnboarding.Action.CONNECT,
                LumenOnboarding.Action.FILE_BEHIND,
                LumenOnboarding.Action.PULL_THREAD,
            ),
            LumenOnboarding.Action.entries.toSet(),
        )
    }

    @Test
    fun `every action explains itself and asks for a yes`() {
        for (action in LumenOnboarding.Action.entries) {
            assertTrue("${action.title} title is blank", action.title.isNotBlank())
            assertTrue(
                "${action.title} explanation is too short to be useful",
                action.what.length >= 40,
            )
            assertTrue(action.confirmLabel.startsWith("Yes — "))
            assertFalse(action.what.contains("TODO"))
        }
    }

    @Test
    fun `explanations actually distinguish the similar actions`() {
        val link = LumenOnboarding.Action.LINK.what
        val connect = LumenOnboarding.Action.CONNECT.what
        // Connect is the suggestion engine; Link is the explicit bond. A
        // newcomer must be able to tell them apart, so neither explanation
        // should be a substring of the other.
        assertFalse(link.contains(connect) || connect.contains(link))
        assertTrue(connect.contains("suggest"))
        assertTrue(link.contains("cross-reference"))
    }
}

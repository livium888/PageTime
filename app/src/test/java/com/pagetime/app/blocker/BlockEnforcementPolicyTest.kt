package com.pagetime.app.blocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockEnforcementPolicyTest {
    @Test
    fun `attached overlay is never shown again`() {
        assertFalse(BlockEnforcementPolicy.shouldShowOverlay(true, "com.example.blocked", "com.example.blocked", 0))
    }

    @Test
    fun `detached overlay retries for the same blocked app at zero`() {
        assertTrue(BlockEnforcementPolicy.shouldShowOverlay(false, "com.example.blocked", "com.example.blocked", 0))
    }

    @Test
    fun `different foreground package cannot trigger redraw`() {
        assertFalse(BlockEnforcementPolicy.shouldShowOverlay(false, "com.example.other", "com.example.blocked", 0))
    }

    @Test
    fun `positive balance cannot trigger overlay`() {
        assertFalse(BlockEnforcementPolicy.shouldShowOverlay(false, "com.example.blocked", "com.example.blocked", 1))
    }

    @Test
    fun `missing current package cannot trigger overlay`() {
        assertFalse(BlockEnforcementPolicy.shouldShowOverlay(false, null, "com.example.blocked", 0))
    }
}

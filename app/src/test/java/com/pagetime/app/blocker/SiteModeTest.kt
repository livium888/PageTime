package com.pagetime.app.blocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteModeTest {

    @Test
    fun `switching from blocklist to allowlist opens a window`() {
        assertTrue(SiteMode.entersAllowlist(SiteMode.BLOCKLIST, SiteMode.ALLOWLIST))
    }

    @Test
    fun `re-selecting allowlist while already in it does not reopen the window`() {
        assertFalse(SiteMode.entersAllowlist(SiteMode.ALLOWLIST, SiteMode.ALLOWLIST))
    }

    @Test
    fun `switching to blocklist never opens an allowlist window, from either mode`() {
        assertFalse(SiteMode.entersAllowlist(SiteMode.BLOCKLIST, SiteMode.BLOCKLIST))
        assertFalse(SiteMode.entersAllowlist(SiteMode.ALLOWLIST, SiteMode.BLOCKLIST))
    }
}

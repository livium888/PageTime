package com.pagetime.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAllowlistTest {

    private val essentials = setOf("com.dialer", "com.sms", "com.launcher", "com.settings", "com.clock", "com.keyboard")
    private val allowed = setOf("com.whatsapp", "com.microsoft.teams")
    private val launchable = setOf("com.whatsapp", "com.microsoft.teams", "com.instagram", "com.dialer", "com.youtube")

    private fun blocked(pkg: String?, mode: AppMode = AppMode.ALLOWLIST, blockedList: Set<String> = emptySet()) =
        AppAllowlist.isBlocked(
            packageName = pkg,
            mode = mode,
            blockedPackages = blockedList,
            allowedPackages = allowed,
            essentials = essentials,
            isLaunchable = { it in launchable },
        )

    // --- Allowlist mode ---

    @Test
    fun `everything launchable is blocked except what was chosen`() {
        assertTrue(blocked("com.instagram"))
        assertTrue(blocked("com.youtube"))
        assertFalse(blocked("com.whatsapp"))
        assertFalse(blocked("com.microsoft.teams"))
    }

    @Test
    fun `essentials are never blocked`() {
        essentials.forEach { assertFalse(it, blocked(it)) }
    }

    @Test
    fun `known authenticators are never blocked, so allowed work apps can still sign in`() {
        assertFalse(blocked("com.azure.authenticator"))
        assertTrue(AppAllowlist.isEssential("com.google.android.apps.authenticator2", emptySet()))
    }

    /** System surfaces (incoming-call screen, permission dialogs) have no launcher entry. */
    @Test
    fun `packages with no launcher entry are never blocked`() {
        assertFalse(blocked("com.android.systemui"))
        assertFalse(blocked("com.android.server.telecom"))
    }

    @Test
    fun `nothing in front is not blocked`() {
        assertFalse(blocked(null))
        assertFalse(blocked(""))
    }

    // --- Blocklist mode is the old rule exactly ---

    @Test
    fun `blocklist mode blocks only what was named`() {
        val list = setOf("com.instagram")
        assertTrue(blocked("com.instagram", AppMode.BLOCKLIST, list))
        assertFalse(blocked("com.youtube", AppMode.BLOCKLIST, list))
    }

    // --- Five, and adding is earned after setup ---

    @Test
    fun `the limit is five`() {
        assertEquals(5, AppAllowlist.MAX_APPS)
        assertTrue(AppAllowlist.canAdd(4, inSetupWindow = true, canLoosen = false))
        assertFalse(AppAllowlist.canAdd(5, inSetupWindow = true, canLoosen = true))
    }

    @Test
    fun `adding is free during setup and earned after it`() {
        assertTrue(AppAllowlist.canAdd(0, inSetupWindow = true, canLoosen = false))
        assertFalse(AppAllowlist.canAdd(0, inSetupWindow = false, canLoosen = false))
        assertTrue(AppAllowlist.canAdd(0, inSetupWindow = false, canLoosen = true))
    }

    @Test
    fun `the setup window lasts thirty minutes`() {
        val t0 = 1_700_000_000_000L
        val until = t0 + AppAllowlist.SETUP_GRACE_MILLIS
        assertTrue(AppAllowlist.inSetupWindow(until, t0))
        assertFalse(AppAllowlist.inSetupWindow(until, until))
        assertEquals(30L * 60_000, AppAllowlist.SETUP_GRACE_MILLIS)
    }

    @Test
    fun `only a genuine move into allowlist opens a setup window`() {
        assertTrue(AppMode.entersAllowlist(AppMode.BLOCKLIST, AppMode.ALLOWLIST))
        assertFalse(AppMode.entersAllowlist(AppMode.ALLOWLIST, AppMode.ALLOWLIST))
        assertFalse(AppMode.entersAllowlist(AppMode.ALLOWLIST, AppMode.BLOCKLIST))
        assertEquals(AppMode.BLOCKLIST, AppMode.fromKey(null))
        assertEquals(AppMode.ALLOWLIST, AppMode.fromKey("allowlist"))
    }
}

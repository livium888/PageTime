package com.pagetime.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRulesDraftTest {

    private val saved = AppRules(AppMode.BLOCKLIST, blocked = setOf("tiktok", "x"), allowed = setOf("bank"))
    private val labels = mapOf("tiktok" to "TikTok", "x" to "X", "bank" to "Bank", "wa" to "WhatsApp", "yt" to "YouTube")
    private fun label(p: String) = labels[p] ?: p

    @Test
    fun `an untouched draft is no change`() {
        val change = AppRulesDraft.diff(saved, saved.copy())
        assertTrue(change.isEmpty)
        assertEquals(0, change.count)
    }

    @Test
    fun `looking at the other mode and coming back changes nothing`() {
        val looked = saved.copy(mode = AppMode.ALLOWLIST)
        assertFalse(AppRulesDraft.diff(saved, looked).isEmpty)
        assertTrue(AppRulesDraft.diff(saved, looked.copy(mode = AppMode.BLOCKLIST)).isEmpty)
    }

    @Test
    fun `every kind of change is counted once`() {
        val draft = AppRules(AppMode.ALLOWLIST, blocked = setOf("tiktok", "yt"), allowed = setOf("wa"))
        val change = AppRulesDraft.diff(saved, draft)
        assertTrue(change.entersAllowlist)
        assertEquals(setOf("yt"), change.block)
        assertEquals(setOf("x"), change.unblock)
        assertEquals(setOf("wa"), change.allow)
        assertEquals(setOf("bank"), change.disallow)
        assertEquals(5, change.count)
    }

    @Test
    fun `entering the allowlist says what stays open and how long there is to undo it`() {
        val draft = saved.copy(mode = AppMode.ALLOWLIST, allowed = setOf("bank", "wa"))
        val lines = AppRulesDraft.summary(AppRulesDraft.diff(saved, draft), draft, ::label)
        assertTrue(lines[0].startsWith("Switch to allowing only chosen apps"))
        assertEquals("Still open: Bank, WhatsApp, plus calls, messages and the other essentials.", lines[1])
        assertTrue(lines[2].contains("30 minutes"))
        // The newly allowed app is not listed a second time.
        assertEquals(3, lines.size)
    }

    @Test
    fun `entering with nothing chosen says so plainly`() {
        val draft = AppRules(AppMode.ALLOWLIST)
        val lines = AppRulesDraft.summary(AppRulesDraft.diff(AppRules(), draft), draft, ::label)
        assertTrue(lines[1].startsWith("You have not chosen any apps"))
    }

    @Test
    fun `ordinary edits are listed by name`() {
        val draft = saved.copy(blocked = setOf("tiktok", "yt"), allowed = emptySet())
        val lines = AppRulesDraft.summary(AppRulesDraft.diff(saved, draft), draft, ::label)
        assertEquals(listOf("Block: YouTube.", "Stop blocking: X.", "Stop allowing: Bank."), lines)
    }

    @Test
    fun `leaving the allowlist says everything else opens again`() {
        val inAllowlist = saved.copy(mode = AppMode.ALLOWLIST)
        val draft = inAllowlist.copy(mode = AppMode.BLOCKLIST)
        val lines = AppRulesDraft.summary(AppRulesDraft.diff(inAllowlist, draft), draft, ::label)
        assertEquals(listOf("Switch back to blocking only chosen apps. Everything else opens again."), lines)
    }
}

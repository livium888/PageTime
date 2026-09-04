package com.pagetime.app.blocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the reason the block screen would not stay up: every
 * window-state event was treated as "the user switched apps", including the
 * events caused by our own overlay appearing.
 */
class ForegroundEventPolicyTest {

    private companion object {
        const val SELF = "com.pagetime.app"
    }

    private fun isChange(pkg: String?, cls: String? = "android.app.Activity") =
        ForegroundEventPolicy.isForegroundChange(pkg, cls, SELF)

    @Test
    fun `a real app coming to the front is a foreground change`() {
        assertTrue(isChange("com.instagram.android"))
        assertTrue(isChange("com.android.chrome"))
    }

    @Test
    fun `our own overlay taking focus is not a foreground change`() {
        // This is the bug: the time-up overlay is focusable, so adding it fired a
        // window-state event for our own package, which dismissed the overlay.
        assertFalse(isChange(SELF, "android.widget.LinearLayout"))
    }

    @Test
    fun `our own reader is not a foreground change`() {
        assertFalse(isChange(SELF, "com.pagetime.app.MainActivity"))
    }

    @Test
    fun `notification shade does not count as leaving the blocked app`() {
        assertFalse(isChange("com.android.systemui", "android.widget.FrameLayout"))
    }

    @Test
    fun `system dialogs do not count as leaving the blocked app`() {
        assertFalse(isChange("android", "android.app.AlertDialog"))
    }

    @Test
    fun `keyboard toasts and popups do not count as leaving the blocked app`() {
        assertFalse(isChange("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow"))
        assertFalse(isChange("com.instagram.android", "android.widget.Toast\$TN"))
        assertFalse(isChange("com.instagram.android", "android.widget.PopupWindow"))
    }

    @Test
    fun `an app class that merely mentions chrome is still a foreground change`() {
        // The `android.` namespace guard: only framework windows are transient, or
        // an app named like the chrome it isn't would slip past the blocker.
        assertTrue(isChange("com.toasted.app", "com.toasted.app.ToastActivity"))
    }

    @Test
    fun `missing package is not a foreground change`() {
        assertFalse(isChange(null))
        assertFalse(isChange(""))
    }

    @Test
    fun `a missing class name does not block a real app switch`() {
        assertTrue(isChange("com.instagram.android", null))
    }

    // ── Poll trust gating (the "overlay when not in the blocked app" bug) ──

    @Test
    fun `poll trusts a real foreign app window`() {
        assertTrue(ForegroundEventPolicy.isTrustedForegroundPackage("com.instagram.android", SELF))
    }

    @Test
    fun `poll does not trust null or blank focus`() {
        // Secure windows, lock screen, uninspectable launcher windows: unknown.
        assertFalse(ForegroundEventPolicy.isTrustedForegroundPackage(null, SELF))
        assertFalse(ForegroundEventPolicy.isTrustedForegroundPackage("", SELF))
    }

    @Test
    fun `poll does not trust our own overlay or reader`() {
        // While the overlay is up, the focused window is ours. Trusting it made
        // the controller believe the user had moved to PageTime — and the 2s
        // poll then re-asserted the overlay over PageTime itself.
        assertFalse(ForegroundEventPolicy.isTrustedForegroundPackage(SELF, SELF))
    }

    @Test
    fun `poll does not trust transient system chrome`() {
        assertFalse(ForegroundEventPolicy.isTrustedForegroundPackage("com.android.systemui", SELF))
        assertFalse(ForegroundEventPolicy.isTrustedForegroundPackage("android", SELF))
    }

    @Test
    fun `a window event is judged by the window in front, not by its own package`() {
        // The bug this exists for. A blocked app sent behind the launcher emits
        // a window-state event on its way out, and goes on emitting them from
        // the background. Acting on the event's package told the blocker the
        // reader was still in that app, so the block screen came back over the
        // home screen — once per event, indefinitely.
        assertEquals(
            "com.android.launcher",
            ForegroundEventPolicy.foregroundForEvent("com.android.launcher", SELF)
        )
    }

    @Test
    fun `an uninspectable window in front is unknown, not a foreground app`() {
        // Many launchers expose no window an accessibility service can read.
        // Unknown must never start or extend a block; the enforcement loop's
        // own staleness check is what ends a block nothing confirms any more.
        assertNull(ForegroundEventPolicy.foregroundForEvent(null, SELF))
        assertNull(ForegroundEventPolicy.foregroundForEvent("", SELF))
    }

    @Test
    fun `our own overlay in front is never a foreground app`() {
        // While the block screen is attached it is what holds focus. Reading
        // that as an app switch would let the overlay dismiss itself.
        assertNull(ForegroundEventPolicy.foregroundForEvent(SELF, SELF))
    }

    @Test
    fun `system chrome in front is unknown`() {
        assertNull(ForegroundEventPolicy.foregroundForEvent("com.android.systemui", SELF))
        assertNull(ForegroundEventPolicy.foregroundForEvent("android", SELF))
    }
}

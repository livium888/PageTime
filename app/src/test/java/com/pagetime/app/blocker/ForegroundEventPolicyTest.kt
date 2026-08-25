package com.pagetime.app.blocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}

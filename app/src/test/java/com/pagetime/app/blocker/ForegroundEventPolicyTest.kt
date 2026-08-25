package com.pagetime.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundEventPolicyTest {

    private val own = "com.pagetime.app"

    @Test
    fun `own package re-asserts instead of clearing the block`() {
        assertEquals(
            ForegroundEventPolicy.Action.REASSERT,
            ForegroundEventPolicy.classify(own, "com.pagetime.app.MainActivity", own)
        )
    }

    @Test
    fun `system ui and framework windows re-assert`() {
        for (pkg in listOf("com.android.systemui", "android")) {
            assertEquals(
                ForegroundEventPolicy.Action.REASSERT,
                ForegroundEventPolicy.classify(pkg, null, own)
            )
        }
    }

    @Test
    fun `permission dialogs re-assert regardless of window class`() {
        assertEquals(
            ForegroundEventPolicy.Action.REASSERT,
            ForegroundEventPolicy.classify("com.google.android.permissioncontroller", null, own)
        )
    }

    @Test
    fun `toasts popups keyboards and dialogs re-assert`() {
        for (windowClass in listOf(
            "android.widget.Toast",
            "android.widget.PopupWindow\$PopupDecorView",
            "com.example.ime.InputMethodService",
            "android.app.Dialog"
        )) {
            assertEquals(
                ForegroundEventPolicy.Action.REASSERT,
                ForegroundEventPolicy.classify("com.instagram.android", windowClass, own)
            )
        }
    }

    @Test
    fun `a genuinely different app is a switch`() {
        assertEquals(
            ForegroundEventPolicy.Action.SWITCH,
            ForegroundEventPolicy.classify("com.instagram.android", "com.instagram.mainactivity.MainActivity", own)
        )
    }

    @Test
    fun `null or blank packages are ignored`() {
        assertEquals(
            ForegroundEventPolicy.Action.IGNORE,
            ForegroundEventPolicy.classify(null, null, own)
        )
        assertEquals(
            ForegroundEventPolicy.Action.IGNORE,
            ForegroundEventPolicy.classify("", null, own)
        )
    }
}

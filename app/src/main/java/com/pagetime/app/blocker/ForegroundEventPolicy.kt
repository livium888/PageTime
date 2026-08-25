package com.pagetime.app.blocker

/**
 * Decides whether a `TYPE_WINDOW_STATE_CHANGED` event really means "a different
 * app is now in front of the user".
 *
 * Window-state events fire for a great many things that are NOT an app switch:
 * our own time-up overlay taking input focus, the notification shade, the
 * keyboard, toasts, and system dialogs. The blocker used to treat every one of
 * them as a foreground change, which cleared `currentBlockedPackage` and tore
 * the block screen down again milliseconds after it appeared — the overlay's own
 * focus event was enough to dismiss the overlay.
 */
object ForegroundEventPolicy {

    /** Packages that own transient system chrome, never a foreground "app". */
    private val TRANSIENT_PACKAGES = setOf(
        "android",
        "com.android.systemui",
    )

    /**
     * Framework window classes that stack on top of the real foreground app.
     * Matched case-insensitively, but only inside the `android.` namespace: the
     * real AOSP names mix cases across OEMs (`android.inputmethodservice.` is
     * lower-case where `Toast` is not), while an app's own activity class must
     * never be mistaken for transient chrome.
     */
    private const val FRAMEWORK_NAMESPACE = "android."

    private val TRANSIENT_CLASS_MARKERS = listOf(
        "toast",
        "popupwindow",
        "inputmethod",
    )

    /**
     * @param packageName the event's package.
     * @param className the event's window/view class, when reported.
     * @param selfPackage PageTime's own package — our overlay and reader must
     *        never be mistaken for the user leaving the blocked app.
     */
    fun isForegroundChange(
        packageName: String?,
        className: String?,
        selfPackage: String
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName == selfPackage) return false
        if (packageName in TRANSIENT_PACKAGES) return false
        if (className != null && isTransientWindowClass(className)) return false
        return true
    }

    private fun isTransientWindowClass(className: String): Boolean {
        if (!className.startsWith(FRAMEWORK_NAMESPACE)) return false
        val lowered = className.lowercase()
        return TRANSIENT_CLASS_MARKERS.any { lowered.contains(it) }
    }
}

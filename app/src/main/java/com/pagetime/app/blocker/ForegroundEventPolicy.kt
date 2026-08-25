package com.pagetime.app.blocker

/**
 * Classifies accessibility foreground events so transient system surfaces never
 * cancel enforcement.
 *
 * The block screen is a window too — and so are the notification shade, the
 * keyboard, toasts, and permission dialogs. Each of those used to look like
 * "the user left the blocked app", tearing enforcement down milliseconds after
 * it appeared, and nothing brought it back because the blocked app emits no new
 * window-state event while it sits in the foreground. This policy makes those
 * events re-assert the block instead; only a genuinely different app counts as
 * leaving.
 */
object ForegroundEventPolicy {

    enum class Action {
        /** Not a foreground-app signal at all; do nothing. */
        IGNORE,

        /** A transient or system surface; keep any active block alive. */
        REASSERT,

        /** The foreground app genuinely changed. */
        SWITCH
    }

    /** Packages that draw system surfaces above apps but are not apps themselves. */
    private val systemSurfaces = setOf(
        "com.android.systemui",
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller"
    )

    /**
     * @param packageName package that owns the window from the event
     * @param windowClass class name of the window (may be null)
     * @param ownPackage PageTime's own package
     */
    fun classify(packageName: String?, windowClass: String?, ownPackage: String): Action {
        if (packageName.isNullOrBlank()) return Action.IGNORE
        // Our own overlay/reader windows fire window-state events for our
        // package; they must never count as leaving the blocked app.
        if (packageName == ownPackage) return Action.REASSERT
        if (packageName in systemSurfaces) return Action.REASSERT
        if (windowClass != null && isTransientWindow(windowClass)) return Action.REASSERT
        return Action.SWITCH
    }

    /**
     * Toasts, popups, dialogs, and input-method windows sit on top of whatever
     * app is foreground; seeing one does not mean the user left it.
     */
    private fun isTransientWindow(windowClass: String): Boolean {
        val lower = windowClass.lowercase()
        return "toast" in lower ||
            "popup" in lower ||
            "inputmethod" in lower ||
            "dialog" in lower
    }
}

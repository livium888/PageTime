package com.pagetime.app.blocker

/**
 * The rules of app allowlist mode, kept free of Android so they can be tested.
 *
 * FIVE, AND NO MORE
 *
 * An allowlist that can grow without limit drifts back into being "everything
 * except the two apps I don't care about". Five forces a choice — the apps
 * genuinely needed day to day — and everything else waits for today's reading.
 *
 * ESSENTIALS DO NOT COUNT AGAINST THE FIVE
 *
 * Calls, texts, the home screen, Settings, the alarm clock and the keyboard
 * are never blocked. Blocking any of them is a safety or lock-out problem, not
 * a discipline problem: a blocked alarm keeps ringing, a blocked keyboard
 * leaves no way to type, a blocked dialer is unacceptable. They are resolved by
 * Android role or intent (default dialer, default SMS app, ...) by the
 * caller, so they are right on any manufacturer's phone, and passed in here as
 * [essentials].
 *
 * ONLY LAUNCHABLE APPS ARE EVER BLOCKED
 *
 * The accessibility service sees windows from every package — the system UI,
 * the incoming-call screen, permission dialogs, share sheets. Blocking "all
 * except" naively would block those too. Restricting the rule to packages
 * with a launcher entry means only things a person deliberately opens can be
 * blocked; every system surface stays untouched.
 */
object AppAllowlist {

    /** The most apps the reader may allow, beyond the essentials. */
    const val MAX_APPS = 5

    /**
     * Free setup time after entering allowlist mode, the same idea and length
     * as [SiteMode.ALLOWLIST_SETUP_GRACE_MILLIS]: the list starts empty, so
     * without a window the reader would be locked out of the apps they need
     * before having read a word. After it, adding costs today's reading.
     */
    const val SETUP_GRACE_MILLIS = 30L * 60_000L

    /**
     * Two-factor apps, allowed without using one of the five: being unable to
     * sign in to an allowed work app (Teams needs an authenticator) would make
     * the allowance meaningless. Matched by exact package name.
     */
    val KNOWN_AUTHENTICATORS: Set<String> = setOf(
        "com.azure.authenticator",                 // Microsoft Authenticator
        "com.google.android.apps.authenticator2",  // Google Authenticator
        "com.authy.authy",                         // Twilio Authy
        "com.duosecurity.duomobile",               // Duo Mobile
        "com.okta.android.auth",                   // Okta Verify
    )

    fun inSetupWindow(setupGraceUntil: Long, nowMillis: Long): Boolean = nowMillis < setupGraceUntil

    /**
     * Whether another app may be added right now. Removing is always allowed:
     * fewer allowed apps is the strict direction.
     */
    fun canAdd(allowedCount: Int, inSetupWindow: Boolean, canLoosen: Boolean): Boolean =
        allowedCount < MAX_APPS && (inSetupWindow || canLoosen)

    /** Essentials never consume one of the five, and are never blocked. */
    fun isEssential(packageName: String, essentials: Set<String>): Boolean =
        packageName in essentials || packageName in KNOWN_AUTHENTICATORS

    /**
     * Whether [packageName] counts as blocked under [mode].
     *
     * Under BLOCKLIST this is exactly the old rule. Under ALLOWLIST it is
     * "launchable, not essential, not chosen". [isLaunchable] is only consulted
     * in allowlist mode, and only after the cheap set checks, because asking
     * PackageManager is the expensive part.
     */
    fun isBlocked(
        packageName: String?,
        mode: AppMode,
        blockedPackages: Set<String>,
        allowedPackages: Set<String>,
        essentials: Set<String>,
        isLaunchable: (String) -> Boolean,
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        return when (mode) {
            AppMode.BLOCKLIST -> packageName in blockedPackages
            AppMode.ALLOWLIST ->
                packageName !in allowedPackages &&
                    !isEssential(packageName, essentials) &&
                    isLaunchable(packageName)
        }
    }
}

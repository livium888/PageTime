package com.pagetime.app.blocker

/**
 * Which direction app rules work in, mirroring [SiteMode] for sites.
 *
 * BLOCKLIST is the original shape: every app opens except the ones the reader
 * named. ALLOWLIST inverts it: every launchable app is blocked except
 * [AppAllowlist.MAX_APPS] the reader chose plus the fixed essentials
 * ([AppAllowlist.isEssential]) — "you can use calls, messages, WhatsApp and
 * Teams; for anything else, read".
 *
 * Both lists persist independently, so switching modes never loses either.
 */
enum class AppMode(val key: String) {
    BLOCKLIST("blocklist"),
    ALLOWLIST("allowlist");

    companion object {
        fun fromKey(key: String?): AppMode = entries.firstOrNull { it.key == key } ?: BLOCKLIST

        /** True only on a genuine transition INTO allowlist mode. */
        fun entersAllowlist(current: AppMode, target: AppMode): Boolean =
            target == ALLOWLIST && current != ALLOWLIST
    }
}

package com.pagetime.app.blocker

/**
 * Which direction a reader's site rules work in.
 *
 * BLOCKLIST is the original shape: permissive by default, every address
 * reachable except what [com.pagetime.app.data.BlockedSiteRepository] names.
 * ALLOWLIST inverts it — restrictive by default, reachable only through
 * what [com.pagetime.app.data.AllowedSiteRepository] names.
 *
 * Both lists persist independently of which mode is active, so switching
 * back and forth never loses either one — a reader who built up a careful
 * blocklist and tries allowlist mode for a week has it waiting, untouched,
 * if they switch back.
 */
enum class SiteMode(val key: String) {
    BLOCKLIST("blocklist"),
    ALLOWLIST("allowlist");

    companion object {
        fun fromKey(key: String?): SiteMode = entries.firstOrNull { it.key == key } ?: BLOCKLIST
    }
}

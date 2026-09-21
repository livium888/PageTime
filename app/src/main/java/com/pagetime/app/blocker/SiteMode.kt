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

        /**
         * How long adding to a freshly-entered allowlist stays free after
         * switching into the mode, before [com.pagetime.app.domain.GateState.canAddAllowedSite]
         * takes back over.
         *
         * Without this, gating every addition behind an earned session traps
         * a reader the instant they switch modes — the allowlist starts
         * empty, so there is nothing to browse to and no way to earn a
         * session to escape (a real bug this shipped with once). Without
         * ANY gate at all, though, adding is free forever, which is just as
         * real a hole: add whatever site you want, right when you want it,
         * and the allowlist stops meaning anything.
         *
         * A one-time setup window is the version that closes both: enough
         * time, once, to build a real starter list without having read a
         * word yet, and after that the same earned cost as unblocking an
         * app. Switching away and back does not refresh it for free either
         * — leaving an active allowlist for a blocklist already costs a
         * session (see [com.pagetime.app.domain.GateState.canSwitchToBlocklist]),
         * so re-entering to mint a new window costs exactly what adding
         * directly would have.
         */
        const val ALLOWLIST_SETUP_GRACE_MILLIS = 30L * 60_000L

        /**
         * Whether moving from [current] to [target] opens a fresh setup
         * window — true only on a genuine transition INTO allowlist mode,
         * never while already there. Kept separate from
         * [com.pagetime.app.data.local.SettingsRepository.setSiteMode]'s
         * DataStore transaction so the rule itself — not just its storage —
         * can be tested directly.
         */
        fun entersAllowlist(current: SiteMode, target: SiteMode): Boolean =
            target == ALLOWLIST && current != ALLOWLIST
    }
}

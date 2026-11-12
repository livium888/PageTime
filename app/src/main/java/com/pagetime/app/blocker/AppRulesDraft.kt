package com.pagetime.app.blocker

/**
 * App rules as the reader is editing them, before anything is applied.
 *
 * NOTHING HAPPENS UNTIL "SAVE CHANGES"
 *
 * These rules can lock a person out of their own phone. They used to apply the
 * instant a switch or a chip was touched, so tapping "Allow only chosen apps"
 * to see what it meant blocked everything on the spot, with no way back until
 * that day's reading was done. Now the screen edits a draft; the saved rules
 * change only when the reader presses Save and confirms a plain statement of
 * what will happen.
 *
 * Pure, so what counts as a change and what the confirmation says can be
 * tested without a phone.
 */
data class AppRules(
    val mode: AppMode = AppMode.BLOCKLIST,
    val blocked: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
)

object AppRulesDraft {

    data class Change(
        val entersAllowlist: Boolean,
        val leavesAllowlist: Boolean,
        val block: Set<String>,
        val unblock: Set<String>,
        val allow: Set<String>,
        val disallow: Set<String>,
    ) {
        val isEmpty: Boolean
            get() = !entersAllowlist && !leavesAllowlist &&
                block.isEmpty() && unblock.isEmpty() && allow.isEmpty() && disallow.isEmpty()

        /** How many separate things would change, for "3 changes not saved". */
        val count: Int
            get() = (if (entersAllowlist || leavesAllowlist) 1 else 0) +
                block.size + unblock.size + allow.size + disallow.size
    }

    fun diff(saved: AppRules, draft: AppRules): Change = Change(
        entersAllowlist = AppMode.entersAllowlist(saved.mode, draft.mode),
        leavesAllowlist = saved.mode == AppMode.ALLOWLIST && draft.mode == AppMode.BLOCKLIST,
        block = draft.blocked - saved.blocked,
        unblock = saved.blocked - draft.blocked,
        allow = draft.allowed - saved.allowed,
        disallow = saved.allowed - draft.allowed,
    )

    /**
     * What saving will do, a sentence per line, for the confirmation.
     *
     * Entering the allowlist is spelled out in full, with the apps that will
     * still open and how long the reader has to change their mind, because it
     * is the one change that can leave them without something they need.
     */
    fun summary(change: Change, draft: AppRules, label: (String) -> String): List<String> {
        fun names(packages: Set<String>) = packages.map(label).sortedBy { it.lowercase() }.joinToString(", ")
        val lines = mutableListOf<String>()
        if (change.entersAllowlist) {
            lines += "Switch to allowing only chosen apps. Every other app will be blocked until each " +
                "day's reading is done."
            lines += if (draft.allowed.isEmpty()) {
                "You have not chosen any apps: only calls, messages and the other essentials will open."
            } else {
                "Still open: ${names(draft.allowed)}, plus calls, messages and the other essentials."
            }
            lines += "You can switch back for the next ${AppAllowlist.SETUP_GRACE_MILLIS / 60_000} minutes. " +
                "After that, switching back waits until that day's reading is done."
        }
        if (change.leavesAllowlist) {
            lines += "Switch back to blocking only chosen apps. Everything else opens again."
        }
        if (change.block.isNotEmpty()) lines += "Block: ${names(change.block)}."
        if (change.unblock.isNotEmpty()) lines += "Stop blocking: ${names(change.unblock)}."
        // On the way into the allowlist the full list was just given.
        if (change.allow.isNotEmpty() && !change.entersAllowlist) lines += "Allow: ${names(change.allow)}."
        if (change.disallow.isNotEmpty()) lines += "Stop allowing: ${names(change.disallow)}."
        return lines
    }
}

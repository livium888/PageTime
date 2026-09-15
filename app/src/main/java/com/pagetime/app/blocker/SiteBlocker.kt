package com.pagetime.app.blocker

import com.pagetime.app.data.BlockedSiteRepository
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.domain.BalanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The reader's site rules, in memory, and the ledger rows a site block leaves.
 *
 * WHY THIS IS NOT PART OF [BlockController]
 *
 * It answers a different question. The controller decides whether the APP in
 * front may be used; this decides whether the PAGE in front may be looked at.
 * A site rule is a boundary the reader set for themselves — which host, which
 * section — and nothing here ever weakens or forgets that boundary. What
 * this DOES share with the controller is the one thing an app already had:
 * a session bought with reading covers it, for exactly as long as the
 * session lasts and not a second longer.
 *
 * It is also deliberately not an enforcement loop. The screen that covers a
 * blocked site belongs to the accessibility service, which is the only thing
 * that can put a window on the display; what lives here is the rule set, the
 * session cover, and the record of what happened.
 *
 * A SESSION COVERS SITES THE SAME WAY IT COVERS APPS
 *
 * This used to say the opposite — "no consultation of the balance, ever" —
 * on the argument that a site rule that could be paid off was a toll on a
 * boundary the reader drew themselves. The reader disagreed: reading two
 * hours to buy thirty minutes of app time and then finding a site still
 * refused inside that window was not the boundary they meant to set, it was
 * the read-a-minute-buy-a-minute loop wearing a different hat. So [accessOpen]
 * mirrors [BalanceManager.gate]'s [com.pagetime.app.domain.GateState.coversSites]
 * exactly the way [rules] mirrors the reader's edits — and [match] returns
 * null outright while it is true, before the rule set is even consulted.
 *
 * What did NOT change: the rule itself. This is a paid-for exception window,
 * not a way to remove a site from the list — that still costs what it always
 * cost (see [BlockedSiteRepository] and [com.pagetime.app.domain.GateState.canRemoveBlockedApps]).
 * And the legacy browse-balance economy — the gate switched off entirely —
 * gets none of this: [coversSites][com.pagetime.app.domain.GateState.coversSites]
 * is deliberately not [com.pagetime.app.domain.GateState.open], because a
 * site rule set up with no gate in play at all would otherwise only ever
 * hold for a reader who had also opted into the reading-time economy.
 *
 * COVERED IS NOT FREE
 *
 * The first version of this stopped at "covered means navigable" and left it
 * there — a pure gate, exactly the shape [match] still is. That left the
 * session's own minutes unspent by the one activity a reader would obviously
 * use them on: a session bought with two hours of reading could then be
 * poured entirely into the site it was meant to be a brief exception for,
 * because nothing charged it anything. [wouldMatch] exists for
 * [BlockController]'s benefit, so it can meter a covered site the same way
 * it meters a blocked app — same seconds, same ticker shape, just identified
 * by a rule instead of a package.
 */
class SiteBlocker(
    private val scope: CoroutineScope,
    private val repository: BlockedSiteRepository,
    private val usageRepository: UsageRepository,
    private val balanceManager: BalanceManager,
) {

    /**
     * The active rules, mirrored for the same reason the controller mirrors the
     * blocked-app set: the decision is made on the main thread inside an
     * accessibility event, and cannot wait for Room.
     */
    @Volatile
    var rules: List<SiteRules.Rule> = emptyList()
        private set

    /**
     * Whether a live session currently covers every site rule.
     *
     * Mirrored from the gate for the same reason [rules] is mirrored from the
     * repository, and read by [match] before the rule set is. Starts `false`
     * — the safe default while nothing has been heard from the gate yet is
     * for rules to hold, not to wave the reader through.
     */
    @Volatile
    var accessOpen: Boolean = false
        private set

    /** The last rule put in the ledger, and when, to absorb a burst of events. */
    private var lastLoggedId: String? = null
    private var lastLoggedAt = 0L

    fun start() {
        scope.launch {
            repository.observeEnabled().collect { sites ->
                rules = sites.map { SiteRules.Rule(host = it.host, pathPrefix = it.pathPrefix) }
            }
        }
        scope.launch {
            balanceManager.gate.collect { gate -> accessOpen = gate.coversSites }
        }
    }

    /**
     * The rule covering an address bar's text, or null.
     *
     * [accessOpen] is checked first and short-circuits the rule walk
     * entirely: the caller has already paid for reading the address bar (see
     * [com.pagetime.app.blocker.AppBlockerService.checkSites]), but there is
     * no reason to compare it against every rule when a session has already
     * answered the only question that matters.
     */
    fun match(rawUrl: String): SiteRules.Rule? {
        if (accessOpen) return null
        return SiteRules.match(rawUrl, rules)
    }

    /**
     * The rule that WOULD cover an address bar's text if a session were not
     * currently paying for it — [match] with [accessOpen] left out of the
     * question entirely.
     *
     * Exists for exactly one caller: metering. A session opening a site does
     * not make the visit free, it makes it something the session is
     * SPENDING on, the same seconds a blocked app would spend — and the
     * meter needs to know which rule that is even while [match] correctly
     * says there is nothing to block.
     */
    fun wouldMatch(rawUrl: String): SiteRules.Rule? = SiteRules.match(rawUrl, rules)

    /**
     * Records a block against the rule that caused it.
     *
     * Logged against the rule's own text rather than a package name, because a
     * site has no package — and that is what makes it appear in the blocking
     * stats under the words the reader typed. Sites and apps land in one list
     * and one count, which is the honest report: the reader wants to know what
     * they kept getting bounced out of, not which of the two tables it came
     * from.
     *
     * Debounced per rule, and only for a few seconds. Re-reading the same
     * address bar a dozen times while a page settles is one block, not a dozen;
     * pressing "Go back" and landing on another blocked page is two.
     */
    fun logBlocked(rule: SiteRules.Rule) {
        val now = System.currentTimeMillis()
        if (rule.id == lastLoggedId && now - lastLoggedAt < REPEAT_LOG_DEBOUNCE_MS) return
        lastLoggedId = rule.id
        lastLoggedAt = now
        scope.launch { usageRepository.log(UsageRepository.TYPE_BLOCKED, rule.id, 0) }
    }

    private companion object {
        const val REPEAT_LOG_DEBOUNCE_MS = 3_000L
    }
}

package com.pagetime.app.blocker

import com.pagetime.app.data.BlockedSiteRepository
import com.pagetime.app.data.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The reader's site rules, in memory, and the ledger rows a site block leaves.
 *
 * WHY THIS IS NOT PART OF [BlockController]
 *
 * It answers a different question. The controller decides whether the APP in
 * front may be used — a question about time, whose answers are the balance and
 * the gate. This decides whether the PAGE in front may be looked at, which is
 * not a question about time at all and is never a matter of having earned
 * something. A site rule is a boundary the reader set for themselves, and it
 * holds whether or not they have minutes to spend, which is exactly why the
 * two cannot share a decision function without one of them becoming a lie.
 *
 * It is also deliberately not an enforcement loop. The screen that covers a
 * blocked site belongs to the accessibility service, which is the only thing
 * that can put a window on the display; what lives here is the rule set and the
 * record of what happened.
 *
 * NO CONSULTATION OF THE BALANCE, EVER
 *
 * Worth stating because it is the surprising behaviour: under the browse
 * balance, with minutes to spend, a blocked site is STILL blocked. The rule
 * says "not this site", not "not this site until I have read enough", and a
 * site rule that could be paid off would be a toll on a boundary the reader
 * drew themselves.
 */
class SiteBlocker(
    private val scope: CoroutineScope,
    private val repository: BlockedSiteRepository,
    private val usageRepository: UsageRepository,
) {

    /**
     * The active rules, mirrored for the same reason the controller mirrors the
     * blocked-app set: the decision is made on the main thread inside an
     * accessibility event, and cannot wait for Room.
     */
    @Volatile
    var rules: List<SiteRules.Rule> = emptyList()
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
    }

    /** The rule covering an address bar's text, or null. */
    fun match(rawUrl: String): SiteRules.Rule? = SiteRules.match(rawUrl, rules)

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

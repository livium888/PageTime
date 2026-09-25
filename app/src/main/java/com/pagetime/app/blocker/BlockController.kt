package com.pagetime.app.blocker

import android.os.PowerManager
import com.pagetime.app.data.BlockedAppRepository
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.usage.PendingLedgerWrites
import com.pagetime.app.domain.BalanceManager
import com.pagetime.app.domain.EmergencyUnlock
import com.pagetime.app.domain.GateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a synchronous in-memory copy of the blocked-package set and the browse
 * balance so the AccessibilityService (which fires events on the main thread)
 * can make decisions instantly.
 *
 * Spending design (durable counter):
 * - Every spent second goes through [BalanceManager.spendSecond], which is
 *   mutex-serialized and writes through to DataStore immediately. The balance
 *   is therefore correct even if the process is killed mid-session — on relaunch
 *   it reloads from DataStore and continues from exactly what's left.
 * - While a blocked app is foreground but the SCREEN IS OFF, time does NOT drain
 *   (nobody is watching; draining would be theft).
 * - Each spend session is summarized into the usage ledger as one SPENT row, and
 *   hitting zero while a blocked app is open logs a BLOCKED row.
 *
 * Enforcement design (level-triggered, not edge-triggered):
 * - Showing the block screen once, in reaction to a single window event, is not
 *   enough: events get coalesced, dropped, or arrive for windows that then steal
 *   focus back. So while a blocked app is foreground and access is denied an
 *   [enforceJob] re-asserts the overlay every [ENFORCE_INTERVAL_MS] until the
 *   user actually leaves the app or the rule stops denying it.
 * - If no overlay window can be added at all, enforcement falls back to sending
 *   the user home, which an accessibility service can always do.
 *
 * TWO RULES, ONE AT A TIME
 *
 * "Access is denied" means one of two different things depending on
 * [GateState.enabled], and everything above is written to hold under both. On
 * the balance it means there is nothing left to spend, and the whole spending
 * apparatus runs. Under the gate it means no session is open, the spend ticker
 * never starts, and the balance is not consulted at all — the reason being
 * that a divisible currency is a toll rather than a boundary, and a minute of
 * reading buying a minute of scrolling is the exact loop the gate exists to
 * break.
 *
 * Under the gate the block screen is also the only place a session can be
 * bought from besides Settings, so this controller is where the door is, not
 * merely where the wall is.
 *
 * Bought app time is METERED by the same spend ticker as the balance: it
 * drains only while a blocked app is genuinely in front with the screen on,
 * and what is left is still there tomorrow. A session that counted down on a
 * wall clock would charge the reader for answering the door.
 */
class BlockController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val blockedAppRepository: BlockedAppRepository,
    private val balanceManager: BalanceManager,
    private val usageRepository: UsageRepository,
    private val powerManager: PowerManager,
    /** PageTime's own package, needed to classify poll results as trusted/unknown. */
    private val selfPackage: String = "com.pagetime.app"
) {

    companion object {
        /** Debounce so sitting on a blocked app at zero doesn't spam BLOCKED rows. */
        private const val BLOCKED_LOG_DEBOUNCE_MS = 60_000L

        /** Retry only if an OEM detached the overlay; never redraw an attached one. */
        private const val ENFORCE_INTERVAL_MS = 2_000L

        /**
         * How long a sighting of the blocked app in front stays good for.
         *
         * Only consulted when the overlay has come down and the loop is about
         * to put it back. The poll runs every two seconds, so a blocked app
         * genuinely still in front re-confirms itself two or three times inside
         * this window; a reader who has gone home confirms nothing, and the
         * block stands down instead of chasing them onto the launcher.
         */
        private const val SEEN_IN_FRONT_TTL_MS = 6_000L

    }

    @Volatile
    var blockedPackages: Set<String> = emptySet()
        private set

    /** False until the blocked-app set has been read from Room at least once. */
    @Volatile
    private var blockedPackagesLoaded = false

    /**
     * False until the gate has been read from settings at least once.
     *
     * [GateState.Unknown] reports itself disabled, and a disabled gate now
     * means nothing is blocked at all — so acting before the real state
     * arrives would wave through the very apps this exists to stop, for as
     * long as the first read takes.
     */
    @Volatile
    private var gateLoaded = false

    /**
     * The access gate, mirrored in memory so a decision never has to wait on a
     * flow:
     * the AccessibilityService decides on the main thread and cannot wait for
     * a database.
     *
     * Starts [GateState.Unknown], so before the flow has said anything the
     * controller behaves exactly as it always did and falls back to the
     * balance. A blocker that guessed "shut" while it was still loading would
     * lock the reader out of their phone on a cold boot.
     */
    @Volatile
    var gate: GateState = GateState.Unknown
        private set

    /**
     * The one app an emergency unlock is currently covering, and until when.
     *
     * Deliberately a package and not a flag. A boolean here would be a general
     * bypass, and the point of the hatch is that it lets the reader into the
     * app they actually need rather than into everything.
     */
    @Volatile
    private var emergencyPackage: String? = null

    @Volatile
    private var emergencyUntil = 0L

    /** When recent emergency unlocks were spent, for the two-a-day limit. */
    @Volatile
    private var emergencyUses: List<Long> = emptyList()

    /** Wall-clock time the non-cancellable hard lock ends (0 = none). While one
     *  is running it withholds the emergency unlock, which is the last way out
     *  the block screen still offers. */
    @Volatile
    private var hardLockUntil = 0L

    @Volatile
    var currentBlockedPackage: String? = null

    /**
     * Set while a blocked SITE's screen covers the app this meter is charging.
     *
     * A third "nobody is looking" condition, beside the screen being off and
     * the app having gone to the background — and it is the same argument in
     * all three. The browser is metered while it is genuinely being used; a
     * full-screen block over the page means the reader is staring at the app's
     * own screen and not at the site, so the seconds are not theirs to be
     * charged for.
     *
     * Written by the accessibility service, which is the only thing that knows
     * a site screen is up, and read by the spend ticker on every tick.
     */
    @Volatile
    var sitePaused: Boolean = false

    /**
     * The rule id of the site currently being metered against the session,
     * or null when nothing is. See [onSiteCoverage].
     */
    @Volatile
    private var currentCoveredSiteId: String? = null

    @Volatile
    private var siteSpendJob: Job? = null

    /** Seconds charged against the site currently open in [currentCoveredSiteId]. */
    @Volatile
    private var siteSessionSpentSeconds = 0L

    @Volatile
    private var siteSessionStartWallAt = 0L

    /**
     * When the blocked app was last PROVEN to be in front, on the monotonic
     * clock — wall time can jump under the app and would make a sighting look
     * arbitrarily old or fresh.
     */
    private var blockedSeenAtMs = 0L
        private set

    /** Last package the service reported, kept so state changes can be re-applied. */
    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var spendJob: Job? = null

    @Volatile
    private var enforceJob: Job? = null

    /** Seconds charged in the currently-open spend session (flushed to the ledger at session end). */
    @Volatile
    private var sessionSpentSeconds = 0L

    /** Wall-clock start of the current spend session; recorded so the UsageStats
     *  reconciler can subtract exactly what the live ticker already charged. */
    @Volatile
    private var sessionStartWallAt = 0L

    private var lastBlockedLogAt = 0L

    /** Keeps flushed SPENT rows ordered ahead of any reconcile that reads them. */
    private val pendingLedgerWrites = PendingLedgerWrites()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    var service: AppBlockerService? = null
        set(value) {
            field = value
            _serviceConnected.value = value != null
        }

    fun start() {
        scope.launch {
            blockedAppRepository.observeEnabled().collect { apps ->
                blockedPackages = apps.map { it.packageName }.toSet()
                val firstLoad = !blockedPackagesLoaded
                blockedPackagesLoaded = true
                // An event can arrive before Room has answered (service connects on
                // boot, user opens a blocked app immediately). Re-decide once the
                // set is known, and again whenever the user edits it.
                if (firstLoad || lastForegroundPackage != null) {
                    onForegroundPackage(lastForegroundPackage)
                }
            }
        }
        scope.launch {
            settingsRepository.settings.collect { s ->
                hardLockUntil = s.hardLockUntil

                val wasDenied = accessDenied()
                emergencyPackage = s.emergencyPackage
                emergencyUntil = s.emergencyUntil
                emergencyUses = s.emergencyUses
                // Pressing the button has to take the block screen down at
                // once; waiting for the next foreground event would look like
                // the button had not worked.
                if (wasDenied != accessDenied()) onAccessChanged()
            }
        }
        scope.launch {
            balanceManager.gate.collect { next ->
                val wasDenied = accessDenied()
                gate = next
                gateLoaded = true
                // Crossing the line mid-session is the moment that matters:
                // the reader finished their two hours while the block screen
                // was up, and it has to come down without them going anywhere.
                if (wasDenied != accessDenied()) onAccessChanged()
                // The site-coverage meter stops on this same tick rather than
                // waiting for the service's next poll. Starting a beat late
                // only costs a couple of unmetered seconds, which is the
                // reader's favour; stopping a beat late would overcharge a
                // page that a rule covers again as of this instant.
                if (!next.coversSites) onSiteCoverage(null)
            }
        }
    }

    /**
     * Whether the gate says no.
     *
     * An emergency unlock is checked first and is scoped to one package, so it
     * can only ever answer for the app it was spent on. Every other app stays
     * exactly as blocked as it was, which is what separates this from the
     * quick-disable that was deleted.
     */
    private fun accessDenied(): Boolean {
        if (emergencyCovers(currentBlockedPackage)) return false
        return BlockEnforcementPolicy.accessDenied(
            gateEnabled = gate.enabled,
            gateOpen = gate.open,
        )
    }

    /** Whether an emergency unlock is currently covering [packageName]. */
    fun emergencyCovers(packageName: String?): Boolean = EmergencyUnlock.covers(
        unlockedPackage = emergencyPackage,
        untilMillis = emergencyUntil,
        packageName = packageName,
        nowMillis = System.currentTimeMillis(),
    )

    /** How many emergency unlocks are left in the rolling day. */
    fun emergencyUsesLeft(): Int =
        EmergencyUnlock.usesLeft(emergencyUses, System.currentTimeMillis())

    /** Seconds until the next emergency unlock returns, or null if one is free. */
    fun emergencyNextAvailableInSeconds(): Long? {
        val now = System.currentTimeMillis()
        val at = EmergencyUnlock.nextAvailableAt(emergencyUses, now) ?: return null
        return ((at - now) / 1000).coerceAtLeast(0L)
    }

    /** Whether the block screen should offer the button at all. */
    fun canUseEmergency(): Boolean = EmergencyUnlock.canUnlock(
        stamps = emergencyUses,
        nowMillis = System.currentTimeMillis(),
        hardLockUntil = hardLockUntil,
    )

    /**
     * Spends an emergency unlock on the app the reader is currently blocked
     * from, and stands the block down if it opened.
     *
     * The package comes from [currentBlockedPackage] rather than being passed
     * in, because that is the one the block screen is covering — there is no
     * way to ask for an app other than the one in front, and that is on
     * purpose.
     */
    fun useEmergencyUnlock() {
        val pkg = currentBlockedPackage ?: return
        scope.launch {
            if (settingsRepository.startEmergencyUnlock(pkg)) {
                emergencyPackage = pkg
                emergencyUntil = System.currentTimeMillis() + EmergencyUnlock.DURATION_SECONDS * 1000
                usageRepository.log(UsageRepository.TYPE_EMERGENCY, pkg, EmergencyUnlock.DURATION_SECONDS)
                if (!accessDenied()) onAccessChanged()
            }
        }
    }

    /**
     * Opens a session from the block screen and, if one opened, stands down.
     *
     * The gate flow will report the change within a second anyway, but a
     * second of the block screen still sitting there after the reader has
     * paid for it reads as the button not having worked.
     */
    fun startSessionFromBlockScreen() {
        scope.launch {
            if (balanceManager.startSession()) {
                gate = balanceManager.gateNow()
                if (!accessDenied()) onAccessChanged()
            }
        }
    }

    fun onForegroundPackage(packageName: String?) {
        if (packageName != null) lastForegroundPackage = packageName
        // Nothing is known about which apps are blocked yet — don't clear an active
        // block on an empty set. The collector above re-runs this once Room answers.
        if (!blockedPackagesLoaded || !gateLoaded) return

        // With the gate off nothing is blocked and nothing is metered. There is
        // no second currency to fall back on any more, so an app the reader
        // blocked is simply theirs again until they switch the gate back on.
        val isBlocked = gate.enabled && packageName != null && packageName in blockedPackages
        if (!isBlocked) {
            if (currentBlockedPackage != null) endSpendSession()
            currentBlockedPackage = null
            stopEnforcing()
            service?.dismissTimeUp()
            return
        }

        // Corroborated: callers only reach here with the package that is
        // actually in front, so this is the sighting the enforcement loop reads.
        // It has to be recorded before the early return below, or a block that
        // is genuinely still current would age into looking stale.
        blockedSeenAtMs = android.os.SystemClock.elapsedRealtime()

        // Same blocked app still foreground and already being handled → no-op.
        if (packageName == currentBlockedPackage &&
            (spendJob?.isActive == true || enforceJob?.isActive == true)
        ) {
            return
        }

        endSpendSession()
        currentBlockedPackage = packageName
        if (accessDenied()) startEnforcing() else startSpending()
    }

    /**
     * Called by the service's 2-second poll with the currently focused window's
     * package. Unlike [onForegroundPackage] this NEVER starts a new block and
     * NEVER ends one: the poll can observe our own overlay, the keyboard, the
     * shade, or a secure window it cannot inspect — none of which prove the
     * user left (or entered) the blocked app. Its only jobs are:
     *  - keep the spend ticker honest: if the poll PROVES a different real app
     *    is in front, end the session (window events may have been missed);
     *  - drop the block when the poll PROVES the user is elsewhere.
     * "Unknown" changes nothing — that is what keeps the block screen from
     * appearing over the home screen or over background apps.
     */
    fun onPolledForeground(packageName: String?) {
        if (!blockedPackagesLoaded) return
        // Unknown focus (null, self overlay, keyboard, shade): stand down, change nothing.
        if (!ForegroundEventPolicy.isTrustedForegroundPackage(packageName, selfPackage)) return
        val pkg = packageName ?: return
        if (pkg == currentBlockedPackage) {
            // The poll can only name a real window, so this is proof the reader
            // is still in the blocked app — which is what keeps the overlay
            // eligible to be re-shown if it gets detached.
            blockedSeenAtMs = android.os.SystemClock.elapsedRealtime()
            return
        }
        if (pkg in blockedPackages) {
            // The poll proved a DIFFERENT blocked app took the front (window events
            // were missed). Treat it as a real switch.
            onForegroundPackage(pkg)
            return
        }
        // The poll proved a real, non-blocked app is in front: whatever block
        // state remains is stale — clear it.
        if (currentBlockedPackage != null) {
            endSpendSession()
            currentBlockedPackage = null
            lastForegroundPackage = null
            stopEnforcing()
            service?.dismissTimeUp()
        }
    }

    /**
     * Re-assert the current decision without treating the trigger as an app switch.
     * Called for window changes that are not foreground changes (notification shade,
     * keyboard, another overlay stacking on top of the block screen).
     */
    fun reassert() {
        // Transient windows are intentionally ignored. The enforcement job owns
        // retries and checks `isTimeUpShowing()` before touching WindowManager.
    }

    /**
     * The user chose "Read now" (or was bounced out), so PageTime itself is now in
     * front. Our own package is deliberately not treated as a foreground change —
     * otherwise the overlay's focus event would dismiss the overlay — so the block
     * has to be released explicitly here, or the enforce loop would re-draw the
     * block screen on top of the reader.
     */
    fun releaseBlock() {
        if (currentBlockedPackage == null) return
        endSpendSession()
        currentBlockedPackage = null
        lastForegroundPackage = null
        stopEnforcing()
        service?.dismissTimeUp()
    }

    private fun onAccessChanged() {
        val pkg = currentBlockedPackage ?: return
        if (accessDenied()) {
            if (spendJob?.isActive != true) startEnforcing()
        } else if (enforceJob?.isActive == true) {
            // The balance was topped up, or app time was just bought while the
            // block screen was up. Either way it comes down.
            stopEnforcing()
            service?.dismissTimeUp()
            if (currentBlockedPackage == pkg) startSpending()
        }
    }

    private fun startSpending() {
        if (spendJob?.isActive == true) return
        val pkg = currentBlockedPackage ?: return
        stopEnforcing()
        // One ticker for both eras. Under the gate it burns bought app time,
        // on the balance the old currency; which counter that is belongs to
        // BalanceManager.spendAccessSecond, not here.
        //
        // The gate had no meter at all for one commit, back when a session was
        // a wall clock. That meant putting the phone down cost you minutes you
        // had paid two hours of reading for — the same theft the screen-off
        // rule below exists to prevent, just harder to notice.
        sessionSpentSeconds = 0
        sessionStartWallAt = System.currentTimeMillis()
        spendJob = scope.launch {
            while (isActive && currentBlockedPackage == pkg) {
                delay(1000)
                if (currentBlockedPackage != pkg) break
                // Screen off → nobody is using the app; don't drain their time.
                if (!powerManager.isInteractive) continue
                // A blocked site's screen is covering the app. Same reasoning:
                // the reader is looking at our block screen, not at the app, and
                // charging them for these seconds would be the theft the check
                // above exists to prevent.
                if (sitePaused) continue

                val remaining = balanceManager.spendAccessSecond()
                sessionSpentSeconds++

                if (remaining <= 0) {
                    flushSpentSession(pkg)
                    startEnforcing()
                    break
                }
            }
        }
    }

    /**
     * Keeps the block screen up for as long as the blocked app is in front with an
     * empty balance. This is the part that makes enforcement stick: a single
     * `showTimeUp()` call can be undone by the very next window change, a re-shown
     * loop cannot.
     */
    private fun startEnforcing() {
        if (enforceJob?.isActive == true) return
        val pkg = currentBlockedPackage ?: return
        logBlocked(pkg)
        enforceJob = scope.launch {
            while (isActive && currentBlockedPackage == pkg && accessDenied()) {
                val svc = service
                when {
                    // The service is reconnecting (process restart, user toggled it).
                    // Keep the state and retry — a missing service is not a failed
                    // overlay, and giving up here would end enforcement for good.
                    svc == null -> Unit

                    // Screen off: nobody can see the block screen, and re-adding a
                    // window every second would burn wakeups in the user's pocket.
                    // The same reason the spend ticker does not drain here.
                    !powerManager.isInteractive -> Unit

                    else -> {
                        // The foreground-change event already proved that `pkg` was
                        // in front. Do not query rootInActiveWindow here: while the
                        // overlay is attached Android reports our overlay (or the
                        // keyboard/shade), and treating that transient result as a
                        // new foreground decision causes the two-second flashing.
                        // `showTimeUp()` is idempotent, but calling it repeatedly can
                        // still cause OEM WindowManager focus churn. Only retry when
                        // the overlay has genuinely disappeared.
                        val attached = svc.isTimeUpShowing()
                        val seenRecently = android.os.SystemClock.elapsedRealtime() -
                            blockedSeenAtMs <= SEEN_IN_FRONT_TTL_MS
                        if (!attached && !seenRecently) {
                            // The overlay is down and nothing has confirmed the
                            // blocked app is still in front. That is what going
                            // home looks like from here, because most launchers
                            // expose no window this service can inspect — so
                            // stand down rather than put the block screen back
                            // over wherever the reader actually is.
                            standDown()
                            break
                        }
                        if (BlockEnforcementPolicy.shouldShowOverlay(
                                overlayAttached = attached,
                                currentBlockedPackage = currentBlockedPackage,
                                expectedBlockedPackage = pkg,
                                accessDenied = accessDenied(),
                                blockedAppSeenRecently = seenRecently
                            )
                        ) {
                            val shown = withContext(Dispatchers.Main) { svc.showTimeUp() }
                            if (!shown) {
                                // No overlay window available at all (permission revoked,
                                // OEM restriction). Fall back to removing them from the app.
                                withContext(Dispatchers.Main) { svc.bounceOut() }
                                break
                            }
                        }
                    }
                }
                delay(ENFORCE_INTERVAL_MS)
            }
        }
    }

    /**
     * Give up the block because there is no longer evidence for it.
     *
     * Not a failure and not the reader earning time back: it is the honest
     * state when the app can no longer tell where they are. The next real
     * foreground event re-blocks in an instant if they were still in the app,
     * and the cost of being wrong this way is a moment of unenforced time
     * rather than a block screen following someone across their phone.
     */
    private fun standDown() {
        endSpendSession()
        currentBlockedPackage = null
        lastForegroundPackage = null
        service?.dismissTimeUp()
    }

    private fun stopEnforcing() {
        enforceJob?.cancel()
        enforceJob = null
    }

    /** Ends the current spend session, flushing its summary to the ledger. */
    private fun endSpendSession() {
        val job = spendJob
        spendJob = null
        job?.cancel()
        val pkg = currentBlockedPackage ?: return
        flushSpentSession(pkg)
    }

    private fun flushSpentSession(pkg: String) {
        if (sessionSpentSeconds > 0) {
            val seconds = sessionSpentSeconds
            val start = sessionStartWallAt
            val end = System.currentTimeMillis()
            sessionSpentSeconds = 0
            sessionStartWallAt = 0L
            pendingLedgerWrites.track(scope) { usageRepository.logSpent(pkg, seconds, start, end) }
        }
    }

    /**
     * Meters a session against the site a reader is currently reading
     * instead of being blocked from — the metering half of "a session
     * covers sites too". Called by the accessibility service whenever it
     * knows which rule the browser's address bar currently sits under, or
     * null when nothing does.
     *
     * A COVERED SITE IS SPENDING, NOT VISITING
     *
     * Before this, a session's minutes were only ever charged for time
     * inside a blocked APP; browsing a site the session happened to be
     * covering cost nothing at all, for as long as the reader stayed on it.
     * That made the exception window unlimited in practice — thirty minutes
     * bought could be poured entirely into the one site a rule exists for,
     * because nothing was watching the clock. [siteId] identifies which
     * rule for the ledger, the same string [SiteBlocker.logBlocked] already
     * logs a refusal against, so a site's spent time and its blocked time
     * land under one name in one table.
     *
     * WHY THIS IS A SECOND TICKER AND NOT A REUSE OF [startSpending]
     *
     * The two can never overlap — the foreground window is either a blocked
     * app or the browser showing a covered site, never both — but they are
     * not the same question. [startSpending]'s ticker is keyed to
     * [currentBlockedPackage] and interleaves with [accessDenied] and the
     * enforce loop; none of that applies here; a
     * covered site is never enforced against; [SiteBlocker.match] already
     * says it is open. Bolting a second identity onto a state machine built
     * for the first would risk the two questions answering each other's.
     *
     * NO SCREEN-OFF CHECK NEEDED BEYOND [PowerManager.isInteractive]
     *
     * There is no site-block overlay to be "nobody is looking" behind, the
     * way [sitePaused] exists for an app: a covered site by definition has
     * no overlay up at all, or it would not be covered, it would be blocked.
     * The only way nobody is looking is the screen being off.
     */
    fun onSiteCoverage(siteId: String?) {
        if (siteId == currentCoveredSiteId) return
        endSiteSpendSession()
        currentCoveredSiteId = siteId
        if (siteId != null) startSiteSpending(siteId)
    }

    private fun startSiteSpending(siteId: String) {
        siteSessionSpentSeconds = 0
        siteSessionStartWallAt = System.currentTimeMillis()
        siteSpendJob = scope.launch {
            while (isActive && currentCoveredSiteId == siteId) {
                delay(1000)
                if (currentCoveredSiteId != siteId) break
                // Screen off → nobody is reading the page; don't drain the
                // session for it. Same rule startSpending applies to apps.
                if (!powerManager.isInteractive) continue

                val remaining = balanceManager.spendAccessSecond()
                siteSessionSpentSeconds++

                if (remaining <= 0) {
                    flushSiteSpentSession(siteId)
                    currentCoveredSiteId = null
                    // The session that was covering this site just ran out
                    // from being spent on it. The next foreground poll's
                    // checkSiteAccessClosed call re-blocks it; this only has
                    // to stop charging for it.
                    break
                }
            }
        }
    }

    /** Ends the current site-spend session, flushing its summary to the ledger. */
    private fun endSiteSpendSession() {
        val job = siteSpendJob
        siteSpendJob = null
        job?.cancel()
        val id = currentCoveredSiteId ?: return
        flushSiteSpentSession(id)
    }

    private fun flushSiteSpentSession(siteId: String) {
        if (siteSessionSpentSeconds > 0) {
            val seconds = siteSessionSpentSeconds
            val start = siteSessionStartWallAt
            val end = System.currentTimeMillis()
            siteSessionSpentSeconds = 0
            siteSessionStartWallAt = 0L
            pendingLedgerWrites.track(scope) { usageRepository.logSpent(siteId, seconds, start, end) }
        }
    }

    private fun logBlocked(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedLogAt < BLOCKED_LOG_DEBOUNCE_MS) return
        lastBlockedLogAt = now
        scope.launch { usageRepository.log(UsageRepository.TYPE_BLOCKED, pkg, 0) }
    }

    /**
     * Suspends until the SPENT rows of any just-ended session have landed. The
     * reconciler calls this before reading the ledger, so a session that ended
     * moments earlier is not charged twice.
     */
    suspend fun awaitLedgerWrites() = pendingLedgerWrites.await()

    // addBalance() was here: a public "give yourself browse time" method,
    // kept for API compatibility with callers that no longer exist. Nothing
    // referenced it. Deleted rather than left, because the one thing this
    // sweep found repeatedly is that unused doors get opened eventually.
}

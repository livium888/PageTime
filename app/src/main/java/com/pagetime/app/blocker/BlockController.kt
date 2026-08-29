package com.pagetime.app.blocker

import android.os.PowerManager
import com.pagetime.app.data.BlockedAppRepository
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.usage.PendingLedgerWrites
import com.pagetime.app.domain.BalanceManager
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
 *   focus back. So while a blocked app is foreground at zero balance an
 *   [enforceJob] re-asserts the overlay every [ENFORCE_INTERVAL_MS] until the
 *   user actually leaves the app or earns time.
 * - If no overlay window can be added at all, enforcement falls back to sending
 *   the user home, which an accessibility service can always do.
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

    }

    @Volatile
    var blockedPackages: Set<String> = emptySet()
        private set

    /** False until the blocked-app set has been read from Room at least once. */
    @Volatile
    private var blockedPackagesLoaded = false

    @Volatile
    var balanceSeconds: Long = 0
        private set

    @Volatile
    var currentBlockedPackage: String? = null
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
                // Mirror persisted balance into memory. During an active spend session
                // our own write-through echoes back here with the same value — harmless.
                val previous = balanceSeconds
                balanceSeconds = s.browseBalanceSeconds
                if (previous != balanceSeconds) onBalanceChanged()
            }
        }
    }

    fun onForegroundPackage(packageName: String?) {
        if (packageName != null) lastForegroundPackage = packageName
        // Nothing is known about which apps are blocked yet — don't clear an active
        // block on an empty set. The collector above re-runs this once Room answers.
        if (!blockedPackagesLoaded) return

        val isBlocked = packageName != null && packageName in blockedPackages
        if (!isBlocked) {
            if (currentBlockedPackage != null) endSpendSession()
            currentBlockedPackage = null
            stopEnforcing()
            service?.dismissTimeUp()
            return
        }

        // Same blocked app still foreground and already being handled → no-op.
        if (packageName == currentBlockedPackage &&
            (spendJob?.isActive == true || enforceJob?.isActive == true)
        ) {
            return
        }

        endSpendSession()
        currentBlockedPackage = packageName
        if (balanceSeconds <= 0) {
            startEnforcing()
        } else {
            startSpending()
        }
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
        if (pkg == currentBlockedPackage) return
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

    private fun onBalanceChanged() {
        val pkg = currentBlockedPackage ?: return
        if (balanceSeconds <= 0) {
            if (spendJob?.isActive != true) startEnforcing()
        } else if (enforceJob?.isActive == true) {
            // Time was earned or refunded while the block screen was up.
            stopEnforcing()
            service?.dismissTimeUp()
            if (currentBlockedPackage == pkg) startSpending()
        }
    }

    private fun startSpending() {
        if (spendJob?.isActive == true) return
        val pkg = currentBlockedPackage ?: return
        stopEnforcing()
        sessionSpentSeconds = 0
        sessionStartWallAt = System.currentTimeMillis()
        spendJob = scope.launch {
            while (isActive && currentBlockedPackage == pkg) {
                delay(1000)
                if (currentBlockedPackage != pkg) break
                // Screen off → nobody is using the app; don't drain their time.
                if (!powerManager.isInteractive) continue

                val remaining = balanceManager.spendSecond()
                balanceSeconds = remaining
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
            while (isActive && currentBlockedPackage == pkg && balanceSeconds <= 0) {
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
                        if (BlockEnforcementPolicy.shouldShowOverlay(
                                overlayAttached = svc.isTimeUpShowing(),
                                currentBlockedPackage = currentBlockedPackage,
                                expectedBlockedPackage = pkg,
                                balanceSeconds = balanceSeconds
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

    /** Manual top-up path (kept for API compatibility); routes through the serialized mutator. */
    fun addBalance(seconds: Long) {
        if (seconds <= 0) return
        scope.launch {
            balanceSeconds = balanceManager.adjustBalance(seconds)
        }
    }
}

package com.pagetime.app.blocker

import android.os.PowerManager
import com.pagetime.app.data.BlockedAppRepository
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.SettingsRepository
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
    private val powerManager: PowerManager
) {

    companion object {
        /** Debounce so sitting on a blocked app at zero doesn't spam BLOCKED rows. */
        private const val BLOCKED_LOG_DEBOUNCE_MS = 60_000L

        /** How often the block screen is re-asserted while it should be up. */
        private const val ENFORCE_INTERVAL_MS = 1_000L
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
     * Re-assert the current decision without treating the trigger as an app switch.
     * Called for window changes that are not foreground changes (notification shade,
     * keyboard, another overlay stacking on top of the block screen).
     */
    fun reassert() {
        if (currentBlockedPackage == null) return
        if (balanceSeconds <= 0) startEnforcing()
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
                val shown = withContext(Dispatchers.Main) { service?.showTimeUp() ?: false }
                if (!shown) {
                    // No overlay window available at all (permission revoked, OEM
                    // restriction). Fall back to removing them from the app.
                    withContext(Dispatchers.Main) { service?.bounceOut() }
                    break
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
            scope.launch { usageRepository.logSpent(pkg, seconds, start, end) }
        }
    }

    private fun logBlocked(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedLogAt < BLOCKED_LOG_DEBOUNCE_MS) return
        lastBlockedLogAt = now
        scope.launch { usageRepository.log(UsageRepository.TYPE_BLOCKED, pkg, 0) }
    }

    /** Manual top-up path (kept for API compatibility); routes through the serialized mutator. */
    fun addBalance(seconds: Long) {
        if (seconds <= 0) return
        scope.launch {
            balanceSeconds = balanceManager.adjustBalance(seconds)
        }
    }
}

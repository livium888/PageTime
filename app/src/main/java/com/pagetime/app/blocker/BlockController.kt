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
    }

    @Volatile
    var blockedPackages: Set<String> = emptySet()
        private set

    @Volatile
    var balanceSeconds: Long = 0
        private set

    @Volatile
    var currentBlockedPackage: String? = null
        private set

    private var spendJob: Job? = null

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
            }
        }
        scope.launch {
            settingsRepository.settings.collect { s ->
                // Mirror persisted balance into memory. During an active spend session
                // our own write-through echoes back here with the same value — harmless.
                balanceSeconds = s.browseBalanceSeconds
                if (balanceSeconds <= 0 && currentBlockedPackage != null && spendJob?.isActive != true) {
                    showTimeUp()
                }
            }
        }
    }

    fun onForegroundPackage(packageName: String?) {
        val isBlocked = packageName != null && packageName in blockedPackages
        if (!isBlocked) {
            if (currentBlockedPackage != null) endSpendSession()
            currentBlockedPackage = null
            service?.dismissTimeUp()
            return
        }

        // Same blocked app still foreground and already being handled → no-op.
        if (packageName == currentBlockedPackage && spendJob?.isActive == true) return

        endSpendSession()
        currentBlockedPackage = packageName
        if (balanceSeconds <= 0) {
            showTimeUp()
        } else {
            startSpending()
        }
    }

    private fun startSpending() {
        if (spendJob?.isActive == true) return
        val pkg = currentBlockedPackage ?: return
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
                    logBlocked(pkg)
                    withContext(Dispatchers.Main) { service?.showTimeUp() }
                    break
                }
            }
        }
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

    private fun showTimeUp() {
        service?.showTimeUp()
        val pkg = currentBlockedPackage ?: return
        logBlocked(pkg)
    }

    /** Manual top-up path (kept for API compatibility); routes through the serialized mutator. */
    fun addBalance(seconds: Long) {
        if (seconds <= 0) return
        scope.launch {
            balanceSeconds = balanceManager.adjustBalance(seconds)
        }
    }
}

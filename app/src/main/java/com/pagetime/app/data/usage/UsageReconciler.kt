package com.pagetime.app.data.usage

import com.pagetime.app.blocker.BlockController
import com.pagetime.app.data.BlockedAppRepository
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.domain.BalanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The durable-counter backstop. While [BlockController] charges blocked-app time
 * live (second by second, only as long as its service process is alive), this
 * reconciler audits what ACTUALLY happened using Android's UsageStatsManager —
 * which keeps recording even when PageTime is force-stopped, crashed, or swiped
 * away.
 *
 * On every sweep it:
 *  1. Reads real foreground time per blocked app for [lastReconcileAt, now].
 *  2. Subtracts the wall-clock windows the live ticker ALREADY charged (ledger
 *     SPENT rows carry windowStart/windowEnd, so no double-charging).
 *  3. Charges the remainder against the browse balance through the same
 *     mutex-serialized [BalanceManager] path as everything else.
 *  4. Records a RECONCILED ledger row (auditable) and advances the checkpoint.
 *
 * This is what makes the answer to "what happened while I was gone?" factual:
 * the number you see after reopening is what UsageStats + the ledger actually
 * agree happened, not just what our own process managed to tick.
 */
class UsageReconciler(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val blockedAppRepository: BlockedAppRepository,
    private val usageRepository: UsageRepository,
    private val balanceManager: BalanceManager,
    private val blockController: BlockController,
    private val reader: UsageStatsReader,
    private val parser: ForegroundParser
) {

    companion object {
        private const val SWEEP_INTERVAL_MS = 10 * 60_000L
        /** Don't bother with gaps shorter than this; also avoids a hot loop. */
        private const val MIN_GAP_MS = 60_000L
    }

    private val reconcileMutex = Mutex()

    fun start() {
        scope.launch {
            // Let settings + blocked-app set + balance load first.
            delay(3_000)
            while (isActive) {
                runReconcile()
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    /** Request an immediate audit from lifecycle/service callbacks. */
    fun requestReconcile() {
        scope.launch { runReconcile() }
    }

    suspend fun runReconcile() = reconcileMutex.withLock {
        if (!reader.isPermissionGranted()) return@withLock

        // A live spend session is in progress: the ticker is charging it, and the
        // session's ledger row isn't written yet, so reconciling now would
        // double-charge the user. Wait for the next sweep.
        if (blockController.currentBlockedPackage != null) return@withLock

        // A session that ended moments ago may still be writing its SPENT row —
        // the flush is launched, not awaited. Reading the ledger before it lands
        // would make alreadyChargedSeconds under-count and charge those seconds a
        // second time, on top of what the live ticker already took.
        blockController.awaitLedgerWrites()

        val now = System.currentTimeMillis()
        val lastReconcile = settingsRepository.lastUsageReconcileAt()
        if (lastReconcile == null) {
            // First ever sweep: just plant the checkpoint. We don't audit the
            // past before the app existed.
            settingsRepository.setLastUsageReconcileAt(now)
            return@withLock
        }
        if (now - lastReconcile < MIN_GAP_MS) return@withLock

        val blocked = blockedAppRepository.observeEnabled().first()
            .map { it.packageName }.toSet()
        if (blocked.isEmpty()) {
            settingsRepository.setLastUsageReconcileAt(now)
            return@withLock
        }

        val fgMillis = parser.screenOnForegroundMillis(
            events = reader.events(lastReconcile, now),
            blockedPackages = blocked,
            from = lastReconcile,
            to = now
        )
        val alreadyCharged = alreadyChargedSeconds(lastReconcile, now)

        for ((pkg, fgMs) in fgMillis) {
            // Round up to whole seconds so a reconciliation never under-charges.
            val fgSeconds = (fgMs + 999) / 1000
            val missed = fgSeconds - (alreadyCharged[pkg] ?: 0L)
            if (missed <= 0) continue

            val before = balanceManager.browseBalance()
            val remaining = balanceManager.adjustBalance(-missed)
            usageRepository.log(UsageRepository.TYPE_RECONCILED, pkg, missed)

            if (before > 0L && remaining <= 0L) {
                usageRepository.log(UsageRepository.TYPE_BLOCKED, pkg, 0)
                // No live overlay here — the user isn't in the app right now; the
                // blocked state will surface the moment they open one.
            }
        }

        settingsRepository.setLastUsageReconcileAt(now)
    }

    /**
     * Sum of live-ticker charged seconds per package for windows overlapping
     * [from, now]. Overlap is computed per session window so a session that
     * started before [from] still gives up only its in-window portion.
     */
    private suspend fun alreadyChargedSeconds(from: Long, to: Long): Map<String, Long> {
        val sessions = usageRepository.spentWithWindows(from)
        val out = mutableMapOf<String, Long>()
        for (s in sessions) {
            val start = s.windowStart ?: continue
            val end = s.windowEnd ?: continue
            val pkg = s.packageName ?: continue
            val overlap = minOf(end, to) - maxOf(start, from)
            if (overlap <= 0) continue
            // The live ticker charged at most `seconds`; cap the credit at that.
            val chargedPortion = minOf(s.seconds, (overlap + 999) / 1000)
            out[pkg] = (out[pkg] ?: 0L) + chargedPortion
        }
        return out
    }
}
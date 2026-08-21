package com.pagetime.app.blocker

import com.pagetime.app.data.BlockedAppRepository
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a synchronous in-memory copy of the blocked-package set and the browse balance so the
 * AccessibilityService (which fires events on the main thread) can make decisions instantly.
 */
class BlockController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val blockedAppRepository: BlockedAppRepository
) {

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

    var service: AppBlockerService? = null

    fun start() {
        scope.launch {
            blockedAppRepository.observeEnabled().collect { apps ->
                blockedPackages = apps.map { it.packageName }.toSet()
            }
        }
        scope.launch {
            settingsRepository.settings.collect { s ->
                balanceSeconds = s.browseBalanceSeconds
                if (balanceSeconds <= 0 && currentBlockedPackage != null) {
                    service?.showTimeUp()
                }
            }
        }
    }

    fun onForegroundPackage(packageName: String?) {
        val isBlocked = packageName != null && packageName in blockedPackages
        if (!isBlocked) {
            currentBlockedPackage = null
            spendJob?.cancel()
            service?.dismissTimeUp()
            return
        }

        currentBlockedPackage = packageName
        if (balanceSeconds <= 0) {
            service?.showTimeUp()
        } else {
            startSpending()
        }
    }

    private fun startSpending() {
        if (spendJob?.isActive == true) return
        spendJob = scope.launch {
            var ticks = 0
            while (isActive && balanceSeconds > 0 && currentBlockedPackage != null) {
                delay(1000)
                if (currentBlockedPackage == null) break
                balanceSeconds--
                ticks++
                if (ticks % 5 == 0 || balanceSeconds <= 0) {
                    settingsRepository.setBrowseBalanceSeconds(balanceSeconds)
                }
                if (balanceSeconds <= 0) {
                    withContext(Dispatchers.Main) { service?.showTimeUp() }
                }
            }
        }
    }

    fun addBalance(seconds: Long) {
        if (seconds <= 0) return
        balanceSeconds += seconds
        scope.launch { settingsRepository.setBrowseBalanceSeconds(balanceSeconds) }
    }
}

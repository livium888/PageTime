package com.pagetime.app.domain

import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the browse balance. ALL mutations — earning from reading, spending in a
 * blocked app, manual edits — go through the same mutex-serialized path, so two
 * writers can never clobber each other (the old design let the reader's earn
 * write and the blocker's spend write race on DataStore and lose updates).
 *
 * DataStore remains the single source of truth: whatever survives here is what
 * the app shows after being swiped away and relaunched.
 */
class BalanceManager(
    private val repository: SettingsRepository,
    private val ledger: UsageRepository? = null
) {

    val browseBalanceSeconds: Flow<Long> =
        repository.settings.map { it.browseBalanceSeconds }

    val totalReadingSeconds: Flow<Long> =
        repository.settings.map { it.totalReadingSeconds }

    val ratio: Flow<Double> =
        repository.settings.map { it.ratio }

    /**
     * The access gate: whether the blocked apps are open, and how far off it is
     * if they are not.
     *
     * Deliberately not derived from [browseBalanceSeconds]. The balance is the
     * old currency and the gate is its replacement, and a gate computed from a
     * balance would be the currency wearing a different label — read a minute,
     * open the apps for a minute, which is exactly the loop this exists to
     * close.
     *
     * With no ledger there is nothing to count, so the gate reports itself off
     * rather than guessing: a blocker that cannot measure reading must not be
     * the thing that locks someone out of their phone.
     */
    val gate: Flow<GateState> = ledger.let { log ->
        if (log == null) flowOf(GateState.Disabled)
        else combine(
            repository.settings,
            log.readingInLastDay(),
            log.planningInLastDay(),
        ) { settings, reading, planning ->
            GateState(
                enabled = settings.gateEnabled,
                readingSeconds = reading,
                planningSeconds = planning,
                thresholdSeconds = settings.gateThresholdSeconds,
                planningCapSeconds = settings.planningCapSeconds,
            )
        }
    }

    suspend fun browseBalance(): Long = repository.browseBalanceSeconds()

    /**
     * The one mutation path. Read-modify-write under a mutex; never negative.
     * Returns the resulting balance so callers (spend ticker) stay in sync with
     * exactly what was persisted.
     */
    suspend fun adjustBalance(deltaSeconds: Long): Long = mutex.withLock {
        val current = repository.browseBalanceSeconds()
        val next = (current + deltaSeconds).coerceAtLeast(0L)
        if (next != current) {
            repository.setBrowseBalanceSeconds(next)
        }
        next
    }

    /** Spend exactly one second of browse time. Returns the remaining balance. */
    suspend fun spendSecond(): Long = adjustBalance(-1L)

    suspend fun earnFromReading(seconds: Long) {
        if (seconds <= 0) return
        mutex.withLock {
            repository.addTotalReadingSeconds(seconds)
            // Under the gate the balance stops growing. It is not spent either
            // — the reader keeps whatever they had — but continuing to credit
            // a currency nobody can spend would quietly bank a month of browse
            // time against the day they switch the gate off, and hand them an
            // afternoon of it as a reward for having read.
            if (!repository.gateEnabled()) {
                repository.addBrowseBalanceSeconds((seconds * repository.ratio()).toLong())
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /**
     * Records time spent planning with the assistant.
     *
     * No balance and no reading total: planning is credit toward the gate and
     * nothing else. Under the old ratio it buys nothing at all, which is
     * correct — it was never part of that bargain — but it is still written
     * down, so the audit screen can account for where the evening went.
     */
    suspend fun earnFromPlanning(seconds: Long) {
        if (seconds <= 0) return
        ledger?.logPlanning(seconds)
    }

    suspend fun setBrowseBalance(seconds: Long) = mutex.withLock {
        repository.setBrowseBalanceSeconds(seconds.coerceAtLeast(0L))
    }

    suspend fun setRatio(value: Double) = repository.setRatio(value)

    private val mutex = Mutex()
}

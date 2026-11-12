package com.pagetime.app.domain

import com.pagetime.app.blocker.AppAllowlist
import com.pagetime.app.blocker.AppMode
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.Settings
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
@OptIn(ExperimentalCoroutinesApi::class)
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
     * The daily reading lock.
     *
     * Reading arrives as a DataStore write, so most changes need no polling.
     * Two do not: the 04:00 reset (the stored tally simply stops counting once
     * the lock day changes) and the end of a wind-down. Both happen because
     * time passed and nothing was written, which is what the slow tick is for —
     * it re-locks the phone within a minute of 04:00.
     */
    val gate: Flow<GateState> =
        repository.settings
            .flatMapLatest { settings -> ticking(settings) }
            .distinctUntilChanged()

    private fun ticking(settings: Settings): Flow<GateState> = flow {
        while (true) {
            emit(stateOf(settings, System.currentTimeMillis()))
            delay(WIND_DOWN_TICK_MS)
        }
    }

    private fun stateOf(settings: Settings, now: Long) = GateState(
        switchedOn = settings.gateEnabled,
        readTodaySeconds = LockDay.readToday(
            storedDay = settings.readTodayLockDay,
            storedSeconds = settings.readTodaySeconds,
            today = LockDay.of(now),
        ),
        disableAtMillis = settings.gateDisableAt,
        nowMillis = now,
        dailyTargetSeconds = settings.dailyTargetSeconds,
    )

    /** The gate right now, for callers that cannot wait for a flow. */
    suspend fun gateNow(): GateState =
        stateOf(repository.settings.first(), System.currentTimeMillis())

    /**
     * Burns one second of whatever is paying for access, and returns what is
     * left of it.
     *
     * Under the reading lock nothing is metered: once today's reading is done
     * the phone is open until 04:00, so there is no counter to burn and this
     * returns [UNMETERED]. The controller's ticker still runs, because it is
     * also what records time spent per app in the usage ledger. On the browse
     * balance it burns the old currency, as it always did.
     */
    suspend fun spendAccessSecond(): Long =
        if (repository.gateEnabled()) UNMETERED else spendSecond()

    /**
     * Turns the stored switch off once its cooling-off has elapsed.
     *
     * Not required for correctness — [GateState.enabled] already reads false —
     * but a switch that shows "on" while behaving as off is a lie the settings
     * screen would have to explain.
     */
    suspend fun settleWindDownIfElapsed() {
        val settings = repository.settings.first()
        if (!settings.gateEnabled) return
        val disableAt = settings.gateDisableAt
        if (disableAt > 0L && System.currentTimeMillis() >= disableAt) {
            repository.settleGateWindDown()
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
            // Counted toward today's target whether or not the lock is on, so
            // switching it on mid-day credits the reading already done today.
            repository.addReadToday(seconds, LockDay.of(System.currentTimeMillis()))
            if (!repository.gateEnabled()) {
                // Under the lock the browse balance stops growing: crediting a
                // currency nobody can spend would bank a month of browse time
                // against the day the lock is switched off.
                repository.addBrowseBalanceSeconds((seconds * repository.ratio()).toLong())
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /**
     * Sets the daily reading target. Returns whether the change was accepted.
     *
     * The fence lives here and not in the screen because a rule enforced only
     * by a slider's travel is enforced for one caller. Raising the target is
     * always allowed; lowering it is loosening, so it waits for today's reading
     * (or for the lock to be off). Serialized with reading so the check and
     * the write cannot interleave with today's target being reached.
     */
    suspend fun setDailyTargetSeconds(seconds: Long): Boolean = mutex.withLock {
        val state = stateOf(repository.settings.first(), System.currentTimeMillis())
        if (!state.canLoosenTheRules && GateState.loosensTarget(state.target, seconds)) {
            return@withLock false
        }
        repository.setDailyTargetSeconds(seconds)
        true
    }

    /**
     * Switches app rules between blocklist and allowlist. Returns whether it
     * was accepted. Entering the allowlist only narrows what opens, so it is
     * always allowed; leaving it reopens everything it did not name, so it is
     * fenced like any other loosening.
     */
    suspend fun setAppMode(mode: AppMode): Boolean = mutex.withLock {
        val settings = repository.settings.first()
        val state = stateOf(settings, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val leaving = settings.appMode == AppMode.ALLOWLIST && mode == AppMode.BLOCKLIST
        val mayLeave = AppAllowlist.canLeave(
            inSetupWindow = AppAllowlist.inSetupWindow(settings.appAllowlistSetupGraceUntil, now),
            canLoosen = state.canSwitchToBlocklist,
        )
        if (leaving && !mayLeave) return@withLock false
        repository.setAppMode(mode)
        true
    }

    /**
     * Allows one more app. Returns whether it is allowed afterwards.
     *
     * Free during the one-time setup window; after that it costs today's
     * reading, the same as any loosening. The five-app limit is enforced again
     * inside the repository's edit, so it holds against concurrent taps.
     */
    suspend fun addAllowedApp(packageName: String): Boolean = mutex.withLock {
        val settings = repository.settings.first()
        val now = System.currentTimeMillis()
        if (packageName in settings.allowedApps) return@withLock true
        val state = stateOf(settings, now)
        val permitted = AppAllowlist.canAdd(
            mode = settings.appMode,
            allowedCount = settings.allowedApps.size,
            inSetupWindow = AppAllowlist.inSetupWindow(settings.appAllowlistSetupGraceUntil, now),
            canLoosen = state.canAddAllowedApp,
        )
        if (!permitted) return@withLock false
        repository.addAllowedApp(packageName)
    }

    /** Fewer allowed apps is the strict direction: always allowed. */
    suspend fun removeAllowedApp(packageName: String) = mutex.withLock {
        repository.removeAllowedApp(packageName)
    }

    /**
     * Whether time in a reader-chosen trusted external reading app (e.g.
     * Kindle) is credited as reading. Off by default — see
     * [com.pagetime.app.data.usage.ExternalReadingTracker] and
     * [com.pagetime.app.data.ExternalReadingAppRepository] for which apps.
     */
    val externalReadingEnabled: Flow<Boolean> =
        repository.settings.map { it.externalReadingEnabled }

    suspend fun setExternalReadingEnabled(value: Boolean) = repository.setExternalReadingEnabled(value)

    /** The most external-reading credit that can be banked per day. */
    val externalReadingDailyCapSeconds: Flow<Long> =
        repository.settings.map { it.externalReadingDailyCapSeconds }

    suspend fun setExternalReadingDailyCap(seconds: Long) = repository.setExternalReadingDailyCapSeconds(seconds)

    /**
     * Award credit for [foregroundSeconds] a trusted external reading app held
     * the foreground with the screen on, as measured by
     * [com.pagetime.app.data.usage.ExternalReadingTracker].
     *
     * Discounted by [ExternalReadingCredit] before it ever reaches here, then
     * capped per day via [DailyEarningCap]. Neither step can tell a page actually being read from a phone merely
     * left open; together they make sure that gap has a small, predictable
     * price rather than an unlimited one.
     *
     * Returns how many seconds were actually paid.
     */
    suspend fun earnFromExternalReading(foregroundSeconds: Long): Long {
        val requested = ExternalReadingCredit.creditedSeconds(foregroundSeconds)
        if (requested <= 0) return 0
        val today = java.time.LocalDate.now().toEpochDay()
        val paid = mutex.withLock {
            val earnedSoFar = DailyEarningCap.earnedSoFar(
                storedEpochDay = repository.externalReadingEarnedEpochDay(),
                today = today,
                storedSeconds = repository.externalReadingEarnedToday(),
            )
            val payable = DailyEarningCap.payable(
                requestedSeconds = requested,
                earnedSoFarToday = earnedSoFar,
                capSeconds = repository.externalReadingDailyCapSeconds(),
            )
            if (payable > 0) {
                repository.setExternalReadingEarnedToday(today, earnedSoFar + payable)
                // Reading in a trusted external app (e.g. Kindle) is reading:
                // it counts toward today's target, after the discount and cap
                // above that bound what an unverifiable signal can pay.
                repository.addReadToday(payable, LockDay.of(System.currentTimeMillis()))
                if (!repository.gateEnabled()) {
                    repository.addBrowseBalanceSeconds(payable)
                }
            }
            payable
        }
        if (paid > 0) {
            ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = paid)
        }
        return paid
    }

    suspend fun setBrowseBalance(seconds: Long) = mutex.withLock {
        repository.setBrowseBalanceSeconds(seconds.coerceAtLeast(0L))
    }

    suspend fun setRatio(value: Double) = repository.setRatio(value)

    private val mutex = Mutex()

    companion object {
        /**
         * The 04:00 reset and the end of a wind-down are the only things that
         * happen without a write. A minute is close enough for both.
         */
        private const val WIND_DOWN_TICK_MS = 60_000L

        /** What [spendAccessSecond] returns when nothing is being metered. */
        const val UNMETERED = Long.MAX_VALUE
    }
}

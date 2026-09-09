package com.pagetime.app.domain

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
     * The access gate, re-evaluated as the clock moves.
     *
     * Two of the three things that can change the answer are not events: a
     * session expiring and a wind-down completing both happen because time
     * passed and nothing was written anywhere. So the settings flow alone
     * would leave a session open until the reader happened to touch something.
     *
     * The tick rate follows what is being watched. While a session runs there
     * is a second-hand on screen and an expiry to enforce, so it ticks every
     * second; otherwise the only deadline is a day away and once a minute is
     * plenty. A blocker that woke every second for the life of the process to
     * watch a clock nobody is reading would be a battery bug.
     */
    val gate: Flow<GateState> =
        repository.settings
            .flatMapLatest { settings -> ticking(settings) }
            .distinctUntilChanged()

    private fun ticking(settings: Settings): Flow<GateState> = flow {
        while (true) {
            val state = stateOf(settings, System.currentTimeMillis())
            emit(state)
            delay(if (state.sessionActive) SESSION_TICK_MS else IDLE_TICK_MS)
        }
    }

    private fun stateOf(settings: Settings, now: Long) = GateState(
        switchedOn = settings.gateEnabled,
        creditSeconds = settings.readingCreditSeconds,
        sessionEndsAtMillis = settings.sessionEndsAt,
        disableAtMillis = settings.gateDisableAt,
        nowMillis = now,
        sessionCostSeconds = settings.sessionCostSeconds,
        sessionLengthSeconds = settings.sessionLengthSeconds,
    )

    /** The gate right now, for callers that cannot wait for a flow. */
    suspend fun gateNow(): GateState =
        stateOf(repository.settings.first(), System.currentTimeMillis())

    /**
     * Spends the banked reading and opens a session. Returns whether one
     * opened.
     *
     * Serialized with every other mutation, and the affordability check lives
     * inside the DataStore edit as well: two taps on the block screen's start
     * button must not both read the same credit and both come back yes.
     */
    suspend fun startSession(): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        val state = stateOf(repository.settings.first(), now)
        if (!state.enabled || state.sessionActive) return@withLock false
        val started = repository.startSessionIfAffordable(now)
        if (started) {
            ledger?.log(UsageRepository.TYPE_SESSION, packageName = null, seconds = state.sessionLengthSeconds)
        }
        started
    }

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
            if (repository.gateEnabled()) {
                // Reading banks credit toward the next session, and the browse
                // balance stops growing. It is not spent either — the reader
                // keeps whatever they had — but continuing to credit a currency
                // nobody can spend would bank a month of browse time against
                // the day they switch the gate off, and hand them an afternoon
                // of it as a reward for having read.
                repository.addReadingCredit(seconds)
            } else {
                repository.addBrowseBalanceSeconds((seconds * repository.ratio()).toLong())
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    suspend fun setBrowseBalance(seconds: Long) = mutex.withLock {
        repository.setBrowseBalanceSeconds(seconds.coerceAtLeast(0L))
    }

    suspend fun setRatio(value: Double) = repository.setRatio(value)

    private val mutex = Mutex()

    private companion object {
        /** A visible countdown deserves a second hand. */
        const val SESSION_TICK_MS = 1_000L

        /** Nothing on this side of the gate changes faster than a minute. */
        const val IDLE_TICK_MS = 60_000L
    }
}

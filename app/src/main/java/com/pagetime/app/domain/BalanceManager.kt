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
     * The access gate.
     *
     * Almost everything here changes because something was written: reading
     * banks credit, buying a session spends it, and the spend ticker writes
     * the remaining second every second it burns one. All of that arrives
     * through the settings flow with no polling at all — which is the quiet
     * benefit of metering the session rather than counting down to a deadline.
     *
     * The one exception is the wind-down, which completes because a day
     * passed and nothing was written anywhere. That is what the slow tick is
     * for; a deadline that far out does not need watching more often.
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
        creditSeconds = settings.readingCreditSeconds,
        sessionSecondsRemaining = settings.sessionSecondsRemaining,
        disableAtMillis = settings.gateDisableAt,
        nowMillis = now,
        sessionCostSeconds = settings.sessionCostSeconds,
        sessionLengthSeconds = settings.sessionLengthSeconds,
    )

    /** The gate right now, for callers that cannot wait for a flow. */
    suspend fun gateNow(): GateState =
        stateOf(repository.settings.first(), System.currentTimeMillis())

    /**
     * Buys app time with banked reading. Returns whether the purchase went
     * through.
     *
     * Serialized with every other mutation, and the affordability check lives
     * inside the DataStore edit as well: two taps on the block screen's start
     * button must not both read the same credit and both come back yes.
     */
    suspend fun startSession(): Boolean = mutex.withLock {
        val state = stateOf(repository.settings.first(), System.currentTimeMillis())
        if (!state.enabled) return@withLock false
        val started = repository.startSessionIfAffordable()
        if (started) {
            ledger?.log(UsageRepository.TYPE_SESSION, packageName = null, seconds = state.sessionLengthSeconds)
        }
        started
    }

    /**
     * Burns one second of whichever counter is currently paying for access,
     * and returns what is left of it.
     *
     * One ticker, two counters. Under the gate it is bought app time; on the
     * browse balance it is the old currency. Keeping the branch here rather
     * than in the controller means the screen-off rule, the ledger flush and
     * the UsageStats reconciliation are written once and cannot drift apart.
     */
    suspend fun spendAccessSecond(): Long =
        if (repository.gateEnabled()) repository.spendSessionSecond() else spendSecond()

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

    /**
     * Browse seconds earned per correct flashcard review (HARD/GOOD/EASY).
     * Configurable in Settings; AGAIN earns nothing.
     */
    val flashcardRewardSeconds: Flow<Long> =
        repository.settings.map { it.flashcardRewardSeconds }

    suspend fun flashcardReward(): Long = repository.flashcardRewardSeconds()

    /**
     * Sets the price of a session. Returns whether the change was accepted.
     *
     * The fence lives here and not in the screen because a rule enforced only
     * by a slider's travel is a rule enforced by one caller. The screen clips
     * the thumb so the reader is never offered a move that will be refused;
     * this is what makes the refusal true.
     *
     * Serialized with every other mutation for the same reason startSession
     * is: reading the gate and then writing against it must not interleave
     * with a session starting or expiring in between.
     */
    suspend fun setSessionCostSeconds(seconds: Long): Boolean = mutex.withLock {
        val state = stateOf(repository.settings.first(), System.currentTimeMillis())
        if (!state.canLoosenTheRules &&
            GateState.loosensCost(state.sessionCostSeconds, seconds)
        ) {
            return@withLock false
        }
        repository.setSessionCostSeconds(seconds)
        true
    }

    /** The same fence on session length, whose easy direction is longer. */
    suspend fun setSessionLengthSeconds(seconds: Long): Boolean = mutex.withLock {
        val state = stateOf(repository.settings.first(), System.currentTimeMillis())
        if (!state.canLoosenTheRules &&
            GateState.loosensLength(state.sessionLengthSeconds, seconds)
        ) {
            return@withLock false
        }
        repository.setSessionLengthSeconds(seconds)
        true
    }

    suspend fun setFlashcardReward(seconds: Long) = repository.setFlashcardRewardSeconds(seconds)

    /**
     * Award the flashcard bonus for one correctly recalled review. AGAIN earns
     * nothing, so guessing your way to browse time is impossible by design.
     *
     * Under the access gate the reward banks reading credit toward the next
     * session — the same currency reading earns — rather than growing a browse
     * balance the gate makes unspendable.
     */
    suspend fun earnFromFlashcard(ratingCorrect: Boolean) {
        if (!ratingCorrect) return
        val seconds = repository.flashcardRewardSeconds()
        if (seconds <= 0) return
        mutex.withLock {
            if (repository.gateEnabled()) {
                repository.addReadingCredit(seconds)
            } else {
                repository.addBrowseBalanceSeconds(seconds)
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /**
     * Browse seconds earned per explain-back attempt marked at least PARTLY.
     * Configurable separately from the flashcard reward: writing a real
     * explanation and being graded on it is minutes of work, not one tap.
     */
    val explainBackRewardSeconds: Flow<Long> =
        repository.settings.map { it.explainBackRewardSeconds }

    suspend fun explainBackReward(): Long = repository.explainBackRewardSeconds()

    suspend fun setExplainBackReward(seconds: Long) = repository.setExplainBackRewardSeconds(seconds)

    /**
     * Award the explain-back bonus for one qualifying evaluation. OFF earns
     * nothing — the same "wrong answer earns nothing" rule flashcards use for
     * AGAIN — so a caller passes whether the verdict cleared that bar, not the
     * verdict itself: this stays ignorant of how explanations are graded.
     */
    suspend fun earnFromExplainBack(worthRewarding: Boolean) {
        if (!worthRewarding) return
        val seconds = repository.explainBackRewardSeconds()
        if (seconds <= 0) return
        mutex.withLock {
            if (repository.gateEnabled()) {
                repository.addReadingCredit(seconds)
            } else {
                repository.addBrowseBalanceSeconds(seconds)
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /** Browse seconds earned per new link made in the slip box. */
    val lumenLinkRewardSeconds: Flow<Long> =
        repository.settings.map { it.lumenLinkRewardSeconds }

    suspend fun lumenLinkReward(): Long = repository.lumenLinkRewardSeconds()

    suspend fun setLumenLinkReward(seconds: Long) = repository.setLumenLinkRewardSeconds(seconds)

    /**
     * Award the slip-box bonus for one new link. [isNewLink] is false when
     * the two cards were already linked — re-confirming an existing
     * connection earns nothing, so re-opening the same pair can't be farmed
     * for repeat rewards.
     */
    suspend fun earnFromLumenLink(isNewLink: Boolean) {
        if (!isNewLink) return
        val seconds = repository.lumenLinkRewardSeconds()
        if (seconds <= 0) return
        mutex.withLock {
            if (repository.gateEnabled()) {
                repository.addReadingCredit(seconds)
            } else {
                repository.addBrowseBalanceSeconds(seconds)
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /** Browse seconds paid out per surprise reading-momentum bonus. */
    val readingMomentumBonusSeconds: Flow<Long> =
        repository.settings.map { it.readingMomentumBonusSeconds }

    suspend fun readingMomentumBonus(): Long = repository.readingMomentumBonusSeconds()

    suspend fun setReadingMomentumBonus(seconds: Long) =
        repository.setReadingMomentumBonusSeconds(seconds)

    /**
     * Pays the reading-momentum bonus [ReadingMomentum] decided is due, and
     * reports how much it paid (0 if the reward is turned off) so the caller
     * can show it.
     *
     * No correctness gate, unlike the flashcard/explain-back/slip-box
     * rewards: every second behind this was already guard-approved credited
     * reading time (the same accrual [earnFromReading] pays for), so there is
     * nothing left to grade — only when to pay it, which is not this
     * function's decision either.
     */
    suspend fun earnReadingMomentumBonus(): Long {
        val seconds = repository.readingMomentumBonusSeconds()
        if (seconds <= 0) return 0
        mutex.withLock {
            if (repository.gateEnabled()) {
                repository.addReadingCredit(seconds)
            } else {
                repository.addBrowseBalanceSeconds(seconds)
            }
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
        return seconds
    }

    suspend fun setBrowseBalance(seconds: Long) = mutex.withLock {
        repository.setBrowseBalanceSeconds(seconds.coerceAtLeast(0L))
    }

    suspend fun setRatio(value: Double) = repository.setRatio(value)

    private val mutex = Mutex()

    private companion object {
        /**
         * The wind-down is the only deadline left, and it is a day away.
         *
         * Everything else reaches this flow as a DataStore write, so there is
         * nothing else worth waking up for.
         */
        const val WIND_DOWN_TICK_MS = 60_000L
    }
}

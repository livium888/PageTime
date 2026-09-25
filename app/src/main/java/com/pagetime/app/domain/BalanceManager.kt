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

    val totalReadingSeconds: Flow<Long> =
        repository.settings.map { it.totalReadingSeconds }

    /**
     * Bought app time not yet burned — the one spendable number left, and the
     * successor to the browse balance this app used to keep alongside it.
     */
    val sessionSecondsRemainingFlow: Flow<Long> =
        repository.settings.map { it.sessionSecondsRemaining }

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
     * Burns one second of bought app time and returns what is left.
     *
     * One ticker, one counter. It used to branch on which of two currencies
     * was live; there is only one now, and with the gate off nothing is
     * blocked, so nothing is metered either.
     */
    suspend fun spendAccessSecond(): Long = repository.spendSessionSecond()

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

    /** How much bought app time is left, for callers that cannot wait for a flow. */
    suspend fun sessionSecondsRemaining(): Long = repository.sessionSecondsRemaining()

    /**
     * Takes [seconds] of bought app time that the live ticker missed — see
     * [com.pagetime.app.data.usage.UsageReconciler]. Returns what is left.
     *
     * The one mutation path for retroactive charging, serialized with every
     * other mutation so an audit landing mid-session cannot clobber the
     * ticker's own write.
     */
    suspend fun chargeMissedSessionSeconds(seconds: Long): Long = mutex.withLock {
        repository.spendSessionSeconds(seconds)
    }

    suspend fun earnFromReading(seconds: Long) {
        if (seconds <= 0) return
        mutex.withLock {
            repository.addTotalReadingSeconds(seconds)
            repository.addReadingCredit(seconds)
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
     * How many of those seconds flashcards (PageTime's own cards and Anki
     * both) can pay in total per day — see [earnFromFlashcard]. Reading
     * itself has no such cap.
     */
    val flashcardDailyCapSeconds: Flow<Long> =
        repository.settings.map { it.flashcardDailyCapSeconds }

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

    /**
     * Award the flashcard bonus for one correctly recalled review (PageTime's
     * own cards and Anki both route through here). AGAIN earns nothing, so
     * guessing your way to browse time is impossible by design.
     *
     * Capped per day via [DailyEarningCap]: a flashcard is a few seconds of
     * work no matter how many are due, which makes it a far better hourly
     * rate than reading unless something limits it — the cap is what keeps
     * flashcards a quick top-up rather than a way to fund a whole day's
     * browsing without ever opening a book. Reading itself carries no cap.
     *
     * Returns how many seconds were actually paid — 0 for AGAIN, for a
     * disabled reward, or once the day's cap is reached — so a caller can
     * show what really happened instead of just the configured reward.
     */
    suspend fun earnFromFlashcard(ratingCorrect: Boolean): Long {
        if (!ratingCorrect) return 0
        val requested = repository.flashcardRewardSeconds()
        if (requested <= 0) return 0
        val today = java.time.LocalDate.now().toEpochDay()
        val paid = mutex.withLock {
            val earnedSoFar = DailyEarningCap.earnedSoFar(
                storedEpochDay = repository.flashcardEarnedEpochDay(),
                today = today,
                storedSeconds = repository.flashcardEarnedToday(),
            )
            val payable = DailyEarningCap.payable(
                requestedSeconds = requested,
                earnedSoFarToday = earnedSoFar,
                capSeconds = repository.flashcardDailyCapSeconds(),
            )
            if (payable > 0) {
                repository.setFlashcardEarnedToday(today, earnedSoFar + payable)
                repository.addReadingCredit(payable)
            }
            payable
        }
        if (paid > 0) {
            ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = paid)
        }
        return paid
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

    /**
     * Award credit for [foregroundSeconds] a trusted external reading app held
     * the foreground with the screen on, as measured by
     * [com.pagetime.app.data.usage.ExternalReadingTracker].
     *
     * Discounted by [ExternalReadingCredit] before it ever reaches here, then
     * capped per day via [DailyEarningCap] — the same two-part bound
     * [earnFromFlashcard] uses for a reward this app also can't fully verify.
     * Neither step can tell a page actually being read from a phone merely
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
                repository.addReadingCredit(payable)
            }
            payable
        }
        if (paid > 0) {
            ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = paid)
        }
        return paid
    }

    /**
     * Browse seconds earned per explain-back attempt marked at least PARTLY.
     * Configurable separately from the flashcard reward: writing a real
     * explanation and being graded on it is minutes of work, not one tap.
     */
    val explainBackRewardSeconds: Flow<Long> =
        repository.settings.map { it.explainBackRewardSeconds }

    suspend fun explainBackReward(): Long = repository.explainBackRewardSeconds()

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
            repository.addReadingCredit(seconds)
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /** Browse seconds earned per new link made in the slip box. */
    val lumenLinkRewardSeconds: Flow<Long> =
        repository.settings.map { it.lumenLinkRewardSeconds }

    suspend fun lumenLinkReward(): Long = repository.lumenLinkRewardSeconds()

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
            repository.addReadingCredit(seconds)
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
    }

    /** Browse seconds paid out per surprise reading-momentum bonus. */
    val readingMomentumBonusSeconds: Flow<Long> =
        repository.settings.map { it.readingMomentumBonusSeconds }

    suspend fun readingMomentumBonus(): Long = repository.readingMomentumBonusSeconds()

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
            repository.addReadingCredit(seconds)
        }
        ledger?.log(UsageRepository.TYPE_EARNED, packageName = null, seconds = seconds)
        return seconds
    }

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

package com.pagetime.app.data.usage

import com.pagetime.app.data.ExternalReadingAppRepository
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
 * Credits reading credit for time spent in apps the reader has explicitly
 * chosen to trust — see [ExternalReadingAppRepository] — using the same
 * UsageStatsManager sweep [UsageReconciler] already runs, and the same
 * "Usage access" permission app blocking already requires.
 *
 * WHY THIS IS NOT ANOTHER [com.pagetime.app.ui.screens.reader.ReadingGuard]
 *
 * PageTime's own reader can tell a page turning from a phone merely left on:
 * it watches real scroll position against a plausible pace. This cannot.
 * All a sweep here ever sees is "a trusted app held the foreground with the
 * screen interactive" — a phone propped up and left alone produces the
 * identical signal, whichever app it's pointed at. That gap is real and is
 * not being hidden; it is bounded instead, the same way a flashcard's much
 * better hourly rate is bounded rather than prevented:
 * [com.pagetime.app.domain.ExternalReadingCredit] pays out a discounted
 * fraction of the foreground time measured, and
 * [BalanceManager.earnFromExternalReading] caps the daily total, so a phone
 * left running on a trusted app costs at most a small, predictable amount
 * rather than an unlimited one, whatever's trusted or how many — which is
 * exactly why the reader picks the set explicitly, in
 * [com.pagetime.app.ui.screens.settings.ExternalReadingAppsScreen], rather
 * than it defaulting to anything.
 *
 * OFF BY DEFAULT
 *
 * Every other reward in [BalanceManager] pays for something PageTime itself
 * verified. This pays on trust, so unlike the flashcard cap — which only
 * limits something the app already grants — it never runs unless the reader
 * explicitly turns it on.
 */
class ExternalReadingTracker(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val externalReadingAppRepository: ExternalReadingAppRepository,
    private val balanceManager: BalanceManager,
    private val reader: UsageStatsReader,
    private val parser: ForegroundParser,
) {

    companion object {
        /**
         * [UsageReconciler] can afford ten minutes between sweeps because it is
         * a backstop behind a ticker that already updates every second. This
         * tracker has no ticker — it is the only thing that ever moves the
         * number a reader watches — so it runs on the same cadence as the
         * floor below, rather than the far coarser one a pure backstop can
         * get away with. A reader who reads for six minutes and checks should
         * see something move, not a still-blank screen for the next four.
         */
        private const val SWEEP_INTERVAL_MS = 60_000L

        /** Don't bother with gaps shorter than this; also avoids a hot loop. */
        private const val MIN_GAP_MS = 60_000L
    }

    private val sweepMutex = Mutex()

    fun start() {
        scope.launch {
            // Let settings load first, same as UsageReconciler.
            delay(3_000)
            while (isActive) {
                runSweep()
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    suspend fun runSweep() = sweepMutex.withLock {
        val now = System.currentTimeMillis()
        if (!settingsRepository.externalReadingEnabled() || !reader.isPermissionGranted()) {
            // Keep the checkpoint current while this can't run, so switching
            // it back on later starts crediting from that moment forward
            // rather than sweeping up whatever trusted-app usage happened
            // during the whole time it was off.
            settingsRepository.setLastExternalReadingCheckAt(now)
            return@withLock
        }

        val last = settingsRepository.lastExternalReadingCheckAt()
        if (last == null) {
            // First sweep ever: plant the checkpoint rather than crediting
            // time from before this tracker existed.
            settingsRepository.setLastExternalReadingCheckAt(now)
            return@withLock
        }
        if (now - last < MIN_GAP_MS) return@withLock

        val trusted = externalReadingAppRepository.observeEnabled().first()
            .map { it.packageName }.toSet()
        if (trusted.isEmpty()) {
            // Nothing chosen yet: advance the checkpoint anyway, so picking an
            // app later starts crediting from that moment rather than
            // sweeping up whatever ran in the meantime.
            settingsRepository.setLastExternalReadingCheckAt(now)
            return@withLock
        }

        val fgMillis = parser.screenOnForegroundMillis(
            events = reader.events(last, now),
            trackedPackages = trusted,
            from = last,
            to = now,
        )
        settingsRepository.setLastExternalReadingCheckAt(now)

        val totalSeconds = fgMillis.values.sum() / 1000
        if (totalSeconds > 0) {
            balanceManager.earnFromExternalReading(totalSeconds)
        }
    }
}

package com.pagetime.app.ui.screens.reader

/**
 * Anti-cheat engine for the reading timer.
 *
 * Goal: a second of browsing credit may only be earned by a human actually reading,
 * not by leaving the app open, not by robotically paging back and forth, and not
 * by flinging through a book faster than any human reads.
 *
 * The engine layers four independent defenses:
 *
 * 1. FOREGROUND + IDLE — the caller only ticks while the reader is resumed
 *    (wired to ON_PAUSE/ON_RESUME), and within that, if no scroll/page-turn
 *    movement happens for [idleTimeoutMs], crediting pauses and an idle gate
 *    is shown. A phone left face-up on the table earns nothing.
 *
 * 2. WATERMARK (anti-oscillation) — the engine tracks the highest progress
 *    point that has already been paid for ("watermark"). Only FORWARD progress
 *    past the watermark mints new credit budget. Paging next/prev between two
 *    pages, or re-reading the same chapter, produces zero new content and thus
 *    zero new budget — the farm dries up after the initial allowance.
 *
 * 3. JUMP DETECTION — an instant progress jump larger than [skipJumpThreshold]
 *    is navigation (TOC jump, seek bar), not reading. The watermark advances so
 *    the skipped region can't be re-farmed later, but no budget is minted.
 *
 * 4. PACE LIMIT — even genuine forward motion faster than plausible (auto-scroll
 *    bots, machine-gun tapping through pages) pauses crediting for
 *    [tooFastCooldownMs]. Measured in words per minute against
 *    [maxPlausibleWordsPerMinute] once a book's word count is known (see
 *    [start]) — falling back to the cruder [maxPlausibleProgressPerMinute]
 *    (book-fraction per minute) only until then. See [ReadingPace] for why
 *    a fraction of the book is not a safe unit on its own: it conflates a
 *    genuinely fast reader with a normal one reading a book that packs more
 *    words per page (a smaller font, or simply a shorter document).
 *
 * Budget economics: one percent of forward book progress mints
 * [secondsOfCreditPerProgress] / 100 seconds of budget, capped at
 * [maxBudgetSeconds]. The defaults are deliberately generous — a person reading
 * at ~1 page per 30–45 s sustains full-rate earning indefinitely — while
 * lingering on a hard page is covered by the starting allowance plus the idle
 * timeout being far longer than a normal page dwell.
 *
 * A fifth defence is structural rather than a rule: [start] happens once per
 * book and [resume] every time the reader returns to the foreground. Only
 * start() grants the opening allowance or moves the watermark backward, so
 * leaving and returning cannot mint anything.
 *
 * All timing uses caller-supplied milliseconds since boot (SystemClock.elapsedRealtime),
 * never System.currentTimeMillis(), so wall-clock changes cannot extend sessions.
 * The class is pure and time-injectable, which makes every cheat scenario unit-testable.
 */
class ReadingGuard(
    private val idleTimeoutMs: Long = 90_000L,
    private val gateGraceMs: Long = 15_000L,
    private val maxPlausibleProgressPerMinute: Float = 0.12f,
    /**
     * Generous ceiling once a book's word count is known — see [ReadingPace].
     * Well above even a fast skilled reader (skilled adults top out around
     * 300-400 wpm per Rayner et al.'s meta-analysis of reading-rate studies)
     * so it only ever catches something no human sustains, not a fast reader.
     */
    private val maxPlausibleWordsPerMinute: Float = 1000f,
    private val tooFastCooldownMs: Long = 60_000L,
    /** Instant progress deltas above this are treated as navigation jumps. */
    private val skipJumpThreshold: Float = 0.05f,
    /** Credit seconds minted per 1.0 of forward book progress. */
    private val secondsOfCreditPerProgress: Float = 90_000f,
    /** Upper bound on banked budget, so bursts of small jumps can't stockpile minutes. */
    private val maxBudgetSeconds: Float = 300f,
    /** Allowance when a session starts, so the first pages earn before the watermark builds. */
    private val startingBudgetSeconds: Float = 120f,
    /** Most words a person can turn through in [FLICK_WINDOW_MS]: 1,000 a minute, well past a fast skimmer. */
    private val maxWordsPerWindow: Int = 500,
    /** A single step larger than this is navigation, not a page turn. */
    private val maxPageStepWords: Int = 1_200,
    private val flickCooldownMs: Long = 90_000L,
    /** The span over which the slowest believable reading is measured. */
    private val floorWindowMs: Long = 180_000L,
    /** Words that must be moved through in [floorWindowMs]: 40 a minute, far below any careful reader. */
    private val minWordsPerFloorWindow: Int = 120,
) {

    data class State(
        val crediting: Boolean = false,
        val showIdleGate: Boolean = false,
        val tooFast: Boolean = false,
        /** Remaining anti-oscillation budget in whole seconds. Diagnostic / UI hint. */
        val budgetSeconds: Int = 0
    )

    var state = State()
        private set

    // --- session state ---
    private var startedAt = 0L
    private var lastMovementAt = 0L
    /** The book's total word count, once known — see [start] and [ReadingPace]. */
    private var totalWords: Int? = null

    // --- watermark machinery ---
    private var watermark = 0f
    private var lastSeenProgress = 0f
    private var lastProgressAt = 0L
    private var budgetSeconds = 0f

    // --- rolling pace window ---
    private var windowStartAt = 0L
    private var windowStartWatermark = 0f
    private var tooFastUntil = 0L
    private var gateHiddenUntil = 0L

    /**
     * Begin reading a book. [now] = SystemClock.elapsedRealtime().
     *
     * [alreadyRead] is how far through this book the reader already is, from
     * the saved position. It seeds the watermark, so content paid for in an
     * earlier sitting cannot mint budget a second time. Without it, closing
     * the book and reopening it made the whole book farmable again — the
     * watermark's entire job, undone by a back button.
     *
     * [totalWords] switches the pace defense to [ReadingPace]'s words-per-minute
     * check instead of the cruder book-fraction one — pass null (the default)
     * when it isn't known yet, which keeps the old behavior exactly.
     *
     * Call this once per book. Coming back from the home screen is [resume].
     */
    fun start(now: Long, alreadyRead: Float = 0f, totalWords: Int? = null) {
        startedAt = now
        lastMovementAt = now
        lastProgressAt = now
        watermark = alreadyRead.coerceIn(0f, 1f)
        lastSeenProgress = watermark
        budgetSeconds = startingBudgetSeconds
        tooFastUntil = 0L
        gateHiddenUntil = 0L
        this.totalWords = totalWords?.takeIf { it > 0 }
        floorSince = now
        windowStartAt = now
        windowStartWatermark = watermark
        recompute(now)
    }

    /**
     * The reader came back to the foreground on a book already being read.
     *
     * THIS USED TO BE [start], AND THAT WAS THE BIGGEST HOLE IN THE ENGINE.
     *
     * start() is wired to ON_RESUME, so every flick to the home screen and
     * back re-ran a full session reset: [startingBudgetSeconds] granted again
     * for no reading, and the watermark dropped to zero so every page already
     * paid for became farmable. Two minutes of credit per flick, and the
     * anti-oscillation defence cleared on demand.
     *
     * At the old one-to-one rate that was worth two minutes of browsing. Under
     * the session gate, where two hours buys thirty minutes, sixty flicks buys
     * the whole thing — so what was a leak became the cheapest way to use the
     * app.
     *
     * Resuming refreshes only liveness: the timestamps that decide whether the
     * reader is idle, and the pace window, which would otherwise measure a
     * minute that included an hour in someone's pocket. Everything earned or
     * spent carries over. In particular the too-fast cooldown SURVIVES, or
     * backgrounding the app would be the way to clear it.
     */
    fun resume(now: Long) {
        lastMovementAt = now
        lastProgressAt = now
        windowStartAt = now
        windowStartWatermark = watermark
        floorSince = now
        recompute(now)
    }

    /**
     * Real user movement (page turn, tap zone, scroll tick) without a known
     * progress value. Keeps the session alive but mints no budget by itself —
     * budget comes only from forward content via [onProgress].
     */
    fun onMovement(now: Long) {
        lastMovementAt = now
        recompute(now)
    }

    /**
     * Normalized 0..1 progress through the whole book, from whatever source is
     * rendering (Readium locator for EPUB, scroll fraction for plain text).
     * Counts as movement AND mints budget for genuinely new forward content.
     */
    fun onProgress(progress: Float, now: Long) {
        val p = progress.coerceIn(0f, 1f)

        // Jump detection: a large delta arriving quickly is navigation (TOC,
        // seek slider), not reading. Advance the watermark across the skipped
        // region (so it can't be re-earned later) but mint nothing.
        val sinceLast = now - lastProgressAt
        val delta = p - lastSeenProgress
        if (sinceLast < 2_000L && kotlin.math.abs(delta) > skipJumpThreshold) {
            if (delta > 0) watermark = maxOf(watermark, p)
        } else if (delta > 0) {
            // Genuine forward reading: mint budget for content past the watermark.
            val newContent = p - watermark
            if (newContent > 0) {
                budgetSeconds = (budgetSeconds + newContent * secondsOfCreditPerProgress)
                    .coerceAtMost(maxBudgetSeconds)
                watermark = p
            }
            // Backward movement mints nothing; the watermark doesn't move back.
        }

        lastSeenProgress = p
        lastProgressAt = now
        lastMovementAt = now
        recompute(now)
    }

    /** User taps "Continue reading" on the idle gate. Hides the gate briefly but grants nothing. */
    // region Flicking through pages
    //
    // A SIXTH DEFENCE, IN WORDS
    //
    // The pace check above measures a whole minute in book-fractions and only
    // pauses: a burst of page turns followed by waiting could leave the minute
    // looking ordinary, and nothing it had already credited was taken back.
    // This one watches the words themselves, over thirty seconds. Turning
    // pages that hold more words than anyone reads in that time pauses the
    // clock for a minute and a half and takes back what was credited in the
    // same thirty seconds, so flicking costs the time just earned rather than
    // being merely unrewarded.
    //
    // A jump (the contents, a search result, the seek bar) is one large step,
    // not a run of page turns, and is not counted.

    private companion object {
        /** The span over which page turns are added up. */
        const val FLICK_WINDOW_MS = 30_000L
    }

    private val recentWords = ArrayDeque<Pair<Long, Int>>()
    private val recentCredits = ArrayDeque<Long>()

    /**
     * The reader moved forward by [words] words of text.
     *
     * Returns the seconds of credit to take back, which is zero unless this
     * step tipped the last thirty seconds into flicking.
     */
    fun onWordsAdvanced(words: Int, now: Long): Int {
        if (words <= 0 || words > maxPageStepWords) return 0
        prune(now)
        recentWords.addLast(now to words)
        val total = recentWords.sumOf { it.second }
        if (total <= maxWordsPerWindow) return 0
        val takeBack = recentCredits.size
        recentWords.clear()
        recentCredits.clear()
        tooFastUntil = maxOf(tooFastUntil, now + flickCooldownMs)
        recompute(now)
        return takeBack
    }

    private fun prune(now: Long) {
        while (recentWords.isNotEmpty() && now - recentWords.first().first >= FLICK_WINDOW_MS) recentWords.removeFirst()
        while (recentCredits.isNotEmpty() && now - recentCredits.first() >= FLICK_WINDOW_MS) recentCredits.removeFirst()
    }

    // endregion

    // region The slowest believable reading
    //
    // THE OPPOSITE OF FLICKING
    //
    // Seconds are what earn time, and seconds alone cannot tell reading from
    // a phone propped open on one page with the odd tap to keep it awake. So
    // time counts only while the reader is moving through the text: at least
    // [minWordsPerFloorWindow] words in any [floorWindowMs]. That is forty a
    // minute, a page every six, which a slow, careful reader is nowhere near
    // and a phone left on a page is. Moving backwards counts, because
    // re-reading is reading.
    //
    // Only judged once the caller has started reporting words, so a reader
    // that cannot measure them is not paused for want of a number it never
    // sent.

    private val floorWords = ArrayDeque<Pair<Long, Int>>()
    private var floorSince = 0L
    private var wordsEverReported = false

    /** The reader moved through [words] words of text, forwards or back. */
    fun onWordsMoved(words: Int, now: Long) {
        if (words <= 0 || words > maxPageStepWords) return
        wordsEverReported = true
        floorWords.addLast(now to words)
        pruneFloor(now)
    }

    private fun pruneFloor(now: Long) {
        while (floorWords.isNotEmpty() && now - floorWords.first().first >= floorWindowMs) floorWords.removeFirst()
    }

    private fun belowFloor(now: Long): Boolean {
        if (!wordsEverReported || now - floorSince < floorWindowMs) return false
        pruneFloor(now)
        return floorWords.sumOf { it.second } < minWordsPerFloorWindow
    }

    // endregion

    fun onContinueTapped(now: Long) {
        gateHiddenUntil = now + gateGraceMs
        recompute(now)
    }

    /** Called once per second by the ticker. Returns whether this second should be credited. */
    fun onTick(now: Long): Boolean {
        // Rolling pace window over genuine forward progress (the watermark only
        // moves forward, so fling-scrolling shows up here even if the user then
        // scrolls back).
        val elapsedInWindow = now - windowStartAt
        if (elapsedInWindow >= 60_000L) {
            val minutes = elapsedInWindow / 60_000f
            val forwardDelta = watermark - windowStartWatermark
            if (ReadingPace.isTooFast(
                    forwardProgressDelta = forwardDelta,
                    minutesElapsed = minutes,
                    totalWords = totalWords,
                    maxFractionPerMinute = maxPlausibleProgressPerMinute,
                    maxWordsPerMinute = maxPlausibleWordsPerMinute,
                )
            ) {
                tooFastUntil = now + tooFastCooldownMs
            }
            windowStartAt = now
            windowStartWatermark = watermark
        }
        recompute(now)
        val credited = state.crediting && budgetSeconds >= 1f
        if (credited) {
            budgetSeconds = (budgetSeconds - 1f).coerceAtLeast(0f)
            recentCredits.addLast(now)
            prune(now)
            recompute(now)
        }
        return credited
    }

    private fun recompute(now: Long): State {
        val idle = now - lastMovementAt > idleTimeoutMs
        val tooFast = now < tooFastUntil
        state = State(
            crediting = !idle && !tooFast && !belowFloor(now),
            showIdleGate = idle && now >= gateHiddenUntil,
            tooFast = tooFast,
            budgetSeconds = budgetSeconds.toInt()
        )
        return state
    }
}

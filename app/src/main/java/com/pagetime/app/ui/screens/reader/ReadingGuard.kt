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
 * 4. PACE LIMIT — even genuine forward motion faster than
 *    [maxPlausibleProgressPerMinute] (auto-scroll bots, machine-gun tapping
 *    through pages) pauses crediting for [tooFastCooldownMs].
 *
 * Budget economics: one percent of forward book progress mints
 * [secondsOfCreditPerProgress] / 100 seconds of budget, capped at
 * [maxBudgetSeconds]. The defaults are deliberately generous — a person reading
 * at ~1 page per 30–45 s sustains full-rate earning indefinitely — while
 * lingering on a hard page is covered by the starting allowance plus the idle
 * timeout being far longer than a normal page dwell.
 *
 * All timing uses caller-supplied milliseconds since boot (SystemClock.elapsedRealtime),
 * never System.currentTimeMillis(), so wall-clock changes cannot extend sessions.
 * The class is pure and time-injectable, which makes every cheat scenario unit-testable.
 */
class ReadingGuard(
    private val idleTimeoutMs: Long = 90_000L,
    private val gateGraceMs: Long = 15_000L,
    private val maxPlausibleProgressPerMinute: Float = 0.12f,
    private val tooFastCooldownMs: Long = 60_000L,
    /** Instant progress deltas above this are treated as navigation jumps. */
    private val skipJumpThreshold: Float = 0.05f,
    /** Credit seconds minted per 1.0 of forward book progress. */
    private val secondsOfCreditPerProgress: Float = 90_000f,
    /** Upper bound on banked budget, so bursts of small jumps can't stockpile minutes. */
    private val maxBudgetSeconds: Float = 300f,
    /** Allowance when a session starts, so the first pages earn before the watermark builds. */
    private val startingBudgetSeconds: Float = 120f
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

    /** Begin a reading session. [now] = SystemClock.elapsedRealtime(). */
    fun start(now: Long) {
        startedAt = now
        lastMovementAt = now
        lastProgressAt = now
        watermark = 0f
        lastSeenProgress = 0f
        budgetSeconds = startingBudgetSeconds
        tooFastUntil = 0L
        gateHiddenUntil = 0L
        windowStartAt = now
        windowStartWatermark = 0f
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
            if (minutes > 0f && forwardDelta / minutes > maxPlausibleProgressPerMinute) {
                tooFastUntil = now + tooFastCooldownMs
            }
            windowStartAt = now
            windowStartWatermark = watermark
        }
        recompute(now)
        val credited = state.crediting && budgetSeconds >= 1f
        if (credited) {
            budgetSeconds = (budgetSeconds - 1f).coerceAtLeast(0f)
            recompute(now)
        }
        return credited
    }

    private fun recompute(now: Long): State {
        val idle = now - lastMovementAt > idleTimeoutMs
        val tooFast = now < tooFastUntil
        state = State(
            crediting = !idle && !tooFast,
            showIdleGate = idle && now >= gateHiddenUntil,
            tooFast = tooFast,
            budgetSeconds = budgetSeconds.toInt()
        )
        return state
    }
}

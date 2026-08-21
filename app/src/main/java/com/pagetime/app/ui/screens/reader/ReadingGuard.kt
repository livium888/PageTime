package com.pagetime.app.ui.screens.reader

/**
 * Anti-cheat gate for the reading timer.
 *
 * The idea: simply leaving the reader open should NOT bank time. Credit is granted only while
 * (a) the user has scrolled/advanced recently, and (b) the reading pace is plausible. If the
 * page hasn't moved for [idleTimeoutMs] the timer pauses and an idle gate is shown; if progress
 * advances faster than [maxProgressPerMinute] (i.e. flinging/skimming, not reading), credit is
 * paused for a cooldown.
 *
 * This is deliberately format-agnostic: the UI feeds it plain "movement" and a normalized
 * 0..1 progress value for both plain-text and EPUB.
 */
class ReadingGuard(
    private val idleTimeoutMs: Long = 90_000L,
    private val gateGraceMs: Long = 15_000L,
    private val maxProgressPerMinute: Float = 0.10f,
    private val tooFastCooldownMs: Long = 60_000L
) {

    data class State(
        val crediting: Boolean = false,
        val showIdleGate: Boolean = false,
        val tooFast: Boolean = false
    )

    var state = State()
        private set

    private var lastMovementAt = 0L
    private var currentProgress = 0f
    private var windowStartAt = 0L
    private var windowStartProgress = 0f
    private var tooFastUntil = 0L
    private var gateHiddenUntil = 0L

    /** Begin a reading session. */
    fun start(now: Long) {
        lastMovementAt = now
        currentProgress = 0f
        windowStartAt = now
        windowStartProgress = 0f
        tooFastUntil = 0L
        gateHiddenUntil = 0L
        recompute(now)
    }

    /** Real user movement (a scroll or a chapter change). This is the only thing that grants credit. */
    fun onMovement(now: Long) {
        lastMovementAt = now
        recompute(now)
    }

    /** Normalized 0..1 progress through the book; also counts as movement. */
    fun onProgress(progress: Float, now: Long) {
        currentProgress = progress.coerceIn(0f, 1f)
        lastMovementAt = now
        recompute(now)
    }

    /** User taps "Continue reading" on the idle gate. Hides the gate briefly but does NOT grant credit. */
    fun onContinueTapped(now: Long) {
        gateHiddenUntil = now + gateGraceMs
        recompute(now)
    }

    /** Called once per second by the ticker. */
    fun onTick(now: Long) {
        if (now - windowStartAt >= 60_000L) {
            val minutes = (now - windowStartAt) / 60_000f
            val delta = currentProgress - windowStartProgress
            if (minutes > 0f && delta / minutes > maxProgressPerMinute) {
                tooFastUntil = now + tooFastCooldownMs
            }
            windowStartAt = now
            windowStartProgress = currentProgress
        }
        recompute(now)
    }

    private fun recompute(now: Long) {
        val idle = now - lastMovementAt > idleTimeoutMs
        val tooFast = now < tooFastUntil
        state = State(
            crediting = !idle && !tooFast,
            showIdleGate = idle && now >= gateHiddenUntil,
            tooFast = tooFast
        )
    }
}

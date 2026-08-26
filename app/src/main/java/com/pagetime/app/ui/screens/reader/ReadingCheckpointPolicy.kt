package com.pagetime.app.ui.screens.reader

/** Keeps automatic comprehension checks occasional and tied to meaningful progress. */
object ReadingCheckpointPolicy {
    const val DEFAULT_INTERVAL_SECONDS = 3 * 60L
    const val MIN_PROGRESS_DELTA = 0.01f

    fun shouldGenerate(
        creditedSeconds: Long,
        progress: Float,
        lastCheckpointProgress: Float,
        generationInProgress: Boolean,
        intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS
    ): Boolean {
        if (generationInProgress || creditedSeconds < intervalSeconds.coerceAtLeast(1L)) return false
        if (progress <= 0f) return false
        return lastCheckpointProgress < 0f || progress - lastCheckpointProgress >= MIN_PROGRESS_DELTA
    }
}

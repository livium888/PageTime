package com.pagetime.app.data.learning

/**
 * Deciding when a generated prompt interrupts the reader.
 *
 * WHY THIS IS ITS OWN THING
 *
 * Interrupting somebody who is reading is the most intrusive act in the app,
 * and the rules for it are easy to get wrong in ways that are hard to see in a
 * screenshot: two prompts at once, a prompt for a passage still ahead of the
 * reader, the same prompt again after it has been answered, five prompts dumped
 * at once because the reader jumped to the end of the chapter. None of those
 * throw. All of them make the feature feel broken.
 *
 * AFTER THE PASSAGE, NOT AT IT
 *
 * A prompt whose fraction is where its passage ENDS surfaces once the reader
 * has gone past it. Asking as they arrive at the paragraph would be asking the
 * question before they have read the answer.
 *
 * ONE AT A TIME, AND NEVER IN A HEAP
 *
 * A reader who skips half a chapter has three prompts become eligible at once.
 * They get the earliest one, and the others wait for the next page turn rather
 * than arriving as a stack. The unit of interruption is one question.
 */
object PromptSurfacing {

    /**
     * The prompt to show now, if any.
     *
     * [progression] is how far through the chapter the reader is, 0 to 1.
     * [judged] holds ids already kept or skipped in this sitting — the database
     * knows too, but a row updates asynchronously and a prompt reappearing for
     * a moment after being dismissed is exactly the kind of flicker that makes
     * a feature feel unfinished.
     */
    fun next(
        pending: List<SurfaceablePrompt>,
        progression: Float,
        judged: Set<String> = emptySet(),
    ): SurfaceablePrompt? =
        pending
            .filter { it.id !in judged && progression >= it.fraction }
            .minByOrNull { it.fraction }

    /**
     * How many of a chapter's prompts the reader has not yet reached.
     *
     * For telling them there is more coming rather than leaving them to
     * discover it, which is the difference between a feature and an ambush.
     */
    fun remaining(
        pending: List<SurfaceablePrompt>,
        progression: Float,
        judged: Set<String> = emptySet(),
    ): Int = pending.count { it.id !in judged && progression < it.fraction }
}

/** The little a surfacing decision needs to know about a prompt. */
data class SurfaceablePrompt(
    val id: String,
    /** Where in the chapter its passage ends, 0 to 1. */
    val fraction: Float,
)

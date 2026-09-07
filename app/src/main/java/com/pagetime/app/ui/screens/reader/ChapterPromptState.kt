package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.learning.ChapterPromptGenerator
import com.pagetime.app.data.learning.SurfaceablePrompt
import com.pagetime.app.data.local.LearningCardEntity

/**
 * The chapter's questions, and which one is due to be asked.
 *
 * One value rather than a sealed hierarchy, for the same reason as the search
 * sheet: a chapter can be generating, already have pending prompts, AND be
 * showing one, and states that overlap are not states.
 */
data class ChapterPromptState(
    val chapterIndex: Int = -1,
    /** Whether this chapter is indexed and a key is configured. */
    val ready: Boolean = false,
    val generating: Boolean = false,
    val stage: ChapterPromptGenerator.Stage? = null,
    /** What the last generation did, or null if none has been run. */
    val result: ChapterPromptGenerator.Result? = null,
    val pending: List<LearningCardEntity> = emptyList(),
    val progression: Float = 0f,
    /** The prompt on screen right now, if any. */
    val surfacedId: String? = null,
    /** Prompts whose passages the reader has not reached yet. */
    val stillAhead: Int = 0,
    val keptCount: Int = 0,
) {
    val surfaceable: List<SurfaceablePrompt>
        get() = pending.map { SurfaceablePrompt(it.id, it.sourceFraction ?: 0f) }

    val surfaced: LearningCardEntity?
        get() = surfacedId?.let { id -> pending.firstOrNull { it.id == id } }

    /**
     * A line telling the reader what just happened.
     *
     * Null while nothing has been run. Every other state says something,
     * including the failures — silence was the whole bug.
     */
    val message: String?
        get() = when {
            generating -> when (stage) {
                ChapterPromptGenerator.Stage.CHOOSING -> "Finding what this chapter is about…"
                ChapterPromptGenerator.Stage.WRITING -> "Writing questions…"
                ChapterPromptGenerator.Stage.CHECKING -> "Checking them against the book…"
                null -> "Starting…"
            }
            result == null -> null
            else -> when (result.outcome) {
                ChapterPromptGenerator.Outcome.MADE,
                ChapterPromptGenerator.Outcome.ALREADY_MADE -> {
                    val n = pending.size
                    if (n == 0) {
                        "You have already answered every question for this chapter."
                    } else {
                        "$n question${if (n == 1) "" else "s"} ready. " +
                            "They appear as you reach the passages they came from."
                    }
                }
                ChapterPromptGenerator.Outcome.NOT_INDEXED ->
                    "This chapter has not been indexed yet — use Search this book first."
                ChapterPromptGenerator.Outcome.NO_KEY ->
                    "No Gemini key is configured, so questions cannot be written."
                ChapterPromptGenerator.Outcome.NOTHING_IN_CHAPTER ->
                    "There is not enough in this chapter to build questions from."
                ChapterPromptGenerator.Outcome.MODEL_RETURNED_NOTHING ->
                    "Gemini answered, but with no questions in it. Worth trying again."
                ChapterPromptGenerator.Outcome.REQUEST_FAILED ->
                    "The request to Gemini failed: ${result.detail ?: "no reason given"}"
                ChapterPromptGenerator.Outcome.ALL_REJECTED ->
                    "${result.offered} question${if (result.offered == 1) "" else "s"} came " +
                        "back and none passed the checks — usually a quote that was not " +
                        "actually in the book. Nothing was saved."
            }
        }

    /** Worth offering the reader the option at all. */
    val offerable: Boolean get() = ready && !generating
}

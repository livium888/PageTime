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
    /** True once generation has been tried, so "none" can be said honestly. */
    val attempted: Boolean = false,
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

    /** Worth offering the reader the option at all. */
    val offerable: Boolean get() = ready && pending.isEmpty() && !attempted && !generating
}

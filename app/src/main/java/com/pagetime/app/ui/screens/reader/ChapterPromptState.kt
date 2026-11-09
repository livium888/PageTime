package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.learning.SurfaceablePrompt
import com.pagetime.app.data.local.LearningCardEntity

/**
 * The chapter's already-made questions, and which one is due to be asked.
 *
 * Nothing writes new questions here any more; cards now come from "Make cards
 * from this stretch". This only shows the ones an earlier version left pending
 * as the reader reaches the passages they came from.
 */
data class ChapterPromptState(
    val chapterIndex: Int = -1,
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
}

package com.pagetime.app.data.learning

import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.local.LearningCardEntity
import io.github.openspacedrepetition.Card
import java.time.Instant

/**
 * The question a reader takes out of a chunk.
 *
 * WHY THIS EXISTS AT ALL
 *
 * Re-reading a chunk was the whole of incremental reading in this app, and
 * re-reading is not what the technique is for. A chunk is supposed to empty
 * itself: the reader passes over it, takes out what matters, and turns that
 * into something they can be asked later. The passage is transport; the
 * question is the cargo. Without this step a chunk of text can only ever come
 * back as the same chunk of text, which is why the loop had no end.
 *
 * WHERE THE CARD GOES
 *
 * Into `learning_cards` — the same table the chapter flashcards use, on the
 * same FSRS calendar, reviewed in the same sitting. A question the reader wrote
 * in their own words while looking at the passage is not a different species of
 * card, and giving it a parallel store would mean two review queues and two
 * answers to "when does this come back".
 *
 * WHY IT IS A PURE FUNCTION
 *
 * The rules that matter are the ones that are easy to get wrong and cheap to
 * test: a card with no question or no answer is not a card and must not be
 * written; a fresh card is NEW to FSRS, with null stability and difficulty,
 * rather than a zeroed card the scheduler will misread as already reviewed; and
 * a manual card is never `generatedByAi`, because [generationKey] is what stops
 * a chapter being regenerated, and a reader's own question must not make the
 * app believe the chapter has been covered.
 */
object ChunkCard {

    /**
     * Builds the card, or null when there is not yet a card to build.
     *
     * [now] is both the creation time and the first due time: the reader has
     * just written the question, so it enters the next review sitting rather
     * than waiting for one. FSRS owns everything after that — the first rating
     * is what sets the real interval.
     */
    fun create(
        id: String,
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String?,
        prompt: String,
        answer: String,
        explanation: String? = null,
        sourceLocator: String? = null,
        sourceFraction: Float = 0f,
        sourceQuote: String? = null,
        now: Long
    ): LearningCardEntity? {
        val question = prompt.trim()
        val reply = answer.trim()
        // A question with no answer is a note, and a card with no question is
        // nothing at all. Neither can be graded, so neither is written.
        if (question.isEmpty() || reply.isEmpty()) return null

        val due = Instant.ofEpochMilli(now)
        return LearningCardEntity(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            prompt = question,
            answer = reply,
            explanation = explanation?.trim()?.takeIf { it.isNotBlank() },
            sourceLocator = sourceLocator,
            sourceFraction = sourceFraction.coerceIn(0f, 1f),
            sourceQuote = sourceQuote?.trim()?.takeIf { it.isNotBlank() },
            cardType = LearningCardEntity.TYPE_QA,
            fsrsCardJson = FsrsCardCodec.toJson(Card.builder().due(due).build()),
            createdAt = now,
            updatedAt = now,
            generatedByAi = false,
            generationKey = null,
            status = LearningCardEntity.STATUS_KEPT,
            dueAt = now,
        )
    }
}

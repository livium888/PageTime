package com.pagetime.app.data.learning

import com.pagetime.app.data.local.LearningCardEntity
import java.util.UUID

/**
 * Turns a pasted reply into cards, in two steps the reader sees between.
 *
 * [review] reads the reply and runs every card through the same checks an
 * AI-generated card passes, against the exact text that was copied. Nothing is
 * stored. The reader then ticks which cards to keep, and [cards] builds only
 * those.
 *
 * THE CHECKS ARE NOT RELAXED BECAUSE A PERSON WAS INVOLVED
 *
 * The reader chose the text, but the model still wrote the cards, and a model
 * asked for a supporting quote will produce a plausible paraphrase of one. The
 * verbatim-quote rule is the only thing standing between an invented fact and
 * a review schedule that rehearses it for months. So a card whose quote is not
 * in the copied text is left out, with the reason shown — never silently.
 */
object ManualCardImport {

    data class Review(
        /** Cards that passed every check, in the order they were written. */
        val accepted: List<RawPrompt>,
        /** Cards that did not, each with the rule that refused it. */
        val leftOut: List<Pair<RawPrompt, PromptRejection>>,
        /** Things in the reply that could not be read as cards at all. */
        val problems: List<String>,
        /** False when the reply held no JSON at all: the wrong thing was pasted. */
        val sawJson: Boolean,
    ) {
        val foundAnything: Boolean get() = accepted.isNotEmpty() || leftOut.isNotEmpty()
    }

    /**
     * [existingPrompts] are the questions the reader already has cards for in
     * this book; a card that repeats one is left out and says so.
     */
    fun review(
        reply: String,
        spanText: String,
        existingPrompts: List<String> = emptyList(),
    ): Review {
        val parsed = PastedCards.parse(reply)
        val verdict = PromptSifter(existingPrompts).sift(parsed.prompts, listOf(spanText))
        return Review(
            accepted = verdict.accepted,
            leftOut = verdict.rejected,
            problems = parsed.problems,
            sawJson = parsed.sawJson,
        )
    }

    /**
     * The stored cards for the ones the reader ticked.
     *
     * Kept and due now, like every hand-made card (see [ChunkCard]): the
     * reader has just seen the text, so the first review is worth the most
     * right away. Never marked as AI-generated, because that flag drives
     * chapter-regeneration bookkeeping that a pasted batch must not touch.
     */
    fun cards(
        chosen: List<RawPrompt>,
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String?,
        endFraction: Float,
        now: Long,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<LearningCardEntity> = chosen.mapNotNull { raw ->
        ChunkCard.create(
            id = newId(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            prompt = raw.prompt,
            answer = raw.answer,
            explanation = raw.explanation,
            sourceFraction = endFraction,
            sourceQuote = raw.sourceQuote,
            now = now,
            cardType = if (raw.isCloze) LearningCardEntity.TYPE_CLOZE else LearningCardEntity.TYPE_QA,
        )
    }
}

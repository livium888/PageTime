package com.pagetime.app.data

/**
 * Prepares the two questions a reader asks about text they pointed at: what
 * does this word mean, and what is this sentence saying.
 *
 * Nothing here calls a model. Each function returns a [ManualAsk]: the prompt
 * the reader takes to their own AI app, and the reader for the reply they
 * paste back. See [ManualAsk].
 *
 * A reply that cannot be read fails rather than falling back. A card has a
 * useful non-AI form — the passage itself — but there is no plain-text version
 * of "what does this mean here", and inventing one would be worse than saying
 * the reply was not usable.
 */
class GlossRepository {

    fun explain(
        term: String,
        before: String,
        after: String,
        bookTitle: String,
    ): Result<ManualAsk<Gloss>> {
        WordGloss.termProblem(term)?.let { return Result.failure(IllegalArgumentException(it)) }

        val sentence = WordGloss.sentenceAround(before, term, after)
        val clean = WordGloss.cleanTerm(term)
        return Result.success(
            ManualAsk(
                heading = clean,
                prompt = WordGloss.prompt(term, before, after, bookTitle),
            ) { reply ->
                runCatching {
                    val parts = WordGloss.parse(reply, term)?.takeIf { !it.isEmpty }
                        ?: throw IllegalStateException(UNREADABLE)
                    Gloss(clean, sentence, parts)
                }
            }
        )
    }

    /**
     * Says [passage] again in words the reader can follow.
     *
     * The safest ask in the app: the model is rewriting text it was handed
     * rather than recalling anything, and the original stays on screen beside
     * the answer, so a reader who cannot judge the rewrite on its own can still
     * see whether the two say the same thing.
     */
    fun simplify(
        passage: String,
        bookTitle: String,
    ): Result<ManualAsk<PlainReading>> {
        PlainEnglish.passageProblem(passage)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val original = passage.trim()
        return Result.success(
            ManualAsk(
                heading = "In plain English",
                prompt = PlainEnglish.prompt(original, bookTitle),
            ) { reply ->
                runCatching {
                    val parts = PlainEnglish.parse(reply)
                        ?: throw IllegalStateException(UNREADABLE)
                    PlainReading(original, parts)
                }
            }
        )
    }

    private companion object {
        const val UNREADABLE =
            "That reply isn't in the format the prompt asked for. Copy the AI's whole reply and try again."
    }
}
